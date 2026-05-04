package protocol.vayu.relay.service.commit.aggregation;

import org.junit.jupiter.api.Test;
import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import protocol.vayu.relay.config.RelayProperties;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolEpochAggregatorTest {

    private static final BigInteger EPOCH_BUDGET = new BigInteger("684931506849315068493");
    private static final long EPOCH_ID = 100L;
    private static final long EPOCH_DURATION = 3600L;

    private final ProtocolEpochAggregator aggregator = new ProtocolEpochAggregator(
            relayProperties(),
            new UniformStakeWeightProvider(),
            new InMemoryPenaltyTracker()
    );

    // ─────────────────────────────────────────────────────────────────────────
    // Active cell filtering
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void cellWithFewerThanThreeReportersShouldBeInactive() {
        List<ReadingSubmissionRequest> readings = List.of(
                reading("0x1111111111111111111111111111111111111111", "0x0882830a1fffffff", 100),
                reading("0x2222222222222222222222222222222222222222", "0x0882830a1fffffff", 120)
        );

        EpochAggregate result = aggregator.aggregate(EPOCH_ID, readings);

        assertEquals(1, result.cells().size());
        assertFalse(result.cells().get(0).active());
        assertEquals(0, result.activeCells());
        assertTrue(result.rewards().isEmpty());
    }

    @Test
    void cellWithThreeOrMoreReportersShouldBeActive() {
        List<ReadingSubmissionRequest> readings = List.of(
                reading("0x1111111111111111111111111111111111111111", "0x0882830a1fffffff", 100),
                reading("0x2222222222222222222222222222222222222222", "0x0882830a1fffffff", 120),
                reading("0x3333333333333333333333333333333333333333", "0x0882830a1fffffff", 130)
        );

        EpochAggregate result = aggregator.aggregate(EPOCH_ID, readings);

        assertTrue(result.cells().get(0).active());
        assertEquals(1, result.activeCells());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Median vs mean
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void medianShouldIgnoreOutlierUnlikeArithmeticMean() {
        // Median of [100, 110, 500] = 110; mean would be 236
        List<ReadingSubmissionRequest> readings = List.of(
                reading("0x1111111111111111111111111111111111111111", "0x0882830a1fffffff", 100),
                reading("0x2222222222222222222222222222222222222222", "0x0882830a1fffffff", 110),
                reading("0x3333333333333333333333333333333333333333", "0x0882830a1fffffff", 500)
        );

        EpochAggregate result = aggregator.aggregate(EPOCH_ID, readings);

        assertEquals(110, result.cells().get(0).medianAqi());
    }

    @Test
    void medianOfEvenCountShouldBeAverageOfTwoMiddleValues() {
        // Sorted: [80, 100, 120, 140] → median = (100+120)/2 = 110
        List<ReadingSubmissionRequest> readings = List.of(
                reading("0x1111111111111111111111111111111111111111", "0x0882830a1fffffff", 100),
                reading("0x2222222222222222222222222222222222222222", "0x0882830a1fffffff", 80),
                reading("0x3333333333333333333333333333333333333333", "0x0882830a1fffffff", 120),
                reading("0x4444444444444444444444444444444444444444", "0x0882830a1fffffff", 140)
        );

        EpochAggregate result = aggregator.aggregate(EPOCH_ID, readings);

        assertEquals(110, result.cells().get(0).medianAqi());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scoring
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void reporterMatchingMedianExactlyShouldScoreOne() {
        // Median = 100. Reporter with aqi=100 → score = 1 - 0/50 = 1.0
        List<ReadingSubmissionRequest> readings = List.of(
                reading("0x1111111111111111111111111111111111111111", "0x0882830a1fffffff", 100),
                reading("0x2222222222222222222222222222222222222222", "0x0882830a1fffffff", 100),
                reading("0x3333333333333333333333333333333333333333", "0x0882830a1fffffff", 100)
        );

        EpochAggregate result = aggregator.aggregate(EPOCH_ID, readings);

        result.cells().get(0).reporterScores()
                .forEach(rs -> assertEquals(1.0, rs.score(), 1e-9));
    }

    @Test
    void reporterDeviatingByFullToleranceShouldScoreZero() {
        // Median = 100. Tolerance = 50. Reporter with aqi=150 → score = 1 - 50/50 = 0.0
        List<ReadingSubmissionRequest> readings = List.of(
                reading("0x1111111111111111111111111111111111111111", "0x0882830a1fffffff", 100),
                reading("0x2222222222222222222222222222222222222222", "0x0882830a1fffffff", 100),
                reading("0x3333333333333333333333333333333333333333", "0x0882830a1fffffff", 150)
        );

        EpochAggregate result = aggregator.aggregate(EPOCH_ID, readings);

        ReporterScore outlier = result.cells().get(0).reporterScores().stream()
                .filter(rs -> rs.reporter().equals("0x3333333333333333333333333333333333333333"))
                .findFirst().orElseThrow();
        assertEquals(0.0, outlier.score(), 1e-9);
    }

    @Test
    void reporterDeviatingBeyondToleranceShouldNotScoreBelowZero() {
        // aqi=200, median=100, tolerance=50 → raw = 1 - 100/50 = -1 → clamped to 0
        List<ReadingSubmissionRequest> readings = List.of(
                reading("0x1111111111111111111111111111111111111111", "0x0882830a1fffffff", 100),
                reading("0x2222222222222222222222222222222222222222", "0x0882830a1fffffff", 100),
                reading("0x3333333333333333333333333333333333333333", "0x0882830a1fffffff", 200)
        );

        EpochAggregate result = aggregator.aggregate(EPOCH_ID, readings);

        ReporterScore outlier = result.cells().get(0).reporterScores().stream()
                .filter(rs -> rs.reporter().equals("0x3333333333333333333333333333333333333333"))
                .findFirst().orElseThrow();
        assertEquals(0.0, outlier.score(), 1e-9);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reward allocation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void rewardsShouldSumToAtMostCellBudget() {
        // 1 active cell, 3 reporters with equal scores
        List<ReadingSubmissionRequest> readings = List.of(
                reading("0x1111111111111111111111111111111111111111", "0x0882830a1fffffff", 100),
                reading("0x2222222222222222222222222222222222222222", "0x0882830a1fffffff", 100),
                reading("0x3333333333333333333333333333333333333333", "0x0882830a1fffffff", 100)
        );

        EpochAggregate result = aggregator.aggregate(EPOCH_ID, readings);

        BigInteger rewardBudget = EPOCH_BUDGET.multiply(BigInteger.valueOf(9800))
                .divide(BigInteger.valueOf(10000));
        BigInteger cellBudget = rewardBudget; // 1 active cell

        BigInteger totalRewarded = result.rewards().stream()
                .map(ReporterReward::amount)
                .reduce(BigInteger.ZERO, BigInteger::add);

        // Total rewarded <= cellBudget (integer division may leave a few wei)
        assertTrue(totalRewarded.compareTo(cellBudget) <= 0,
                "rewarded " + totalRewarded + " > cellBudget " + cellBudget);
        // Sanity: should be close (within 3 wei for integer division rounding)
        assertTrue(cellBudget.subtract(totalRewarded).compareTo(BigInteger.valueOf(3)) <= 0);
    }

    @Test
    void zeroScoreReporterShouldReceiveNoReward() {
        // reporter3 deviates by full tolerance → score 0 → no reward
        List<ReadingSubmissionRequest> readings = List.of(
                reading("0x1111111111111111111111111111111111111111", "0x0882830a1fffffff", 100),
                reading("0x2222222222222222222222222222222222222222", "0x0882830a1fffffff", 100),
                reading("0x3333333333333333333333333333333333333333", "0x0882830a1fffffff", 150)
        );

        EpochAggregate result = aggregator.aggregate(EPOCH_ID, readings);

        boolean outlierRewarded = result.rewards().stream()
                .anyMatch(rr -> rr.reporter().equals("0x3333333333333333333333333333333333333333"));
        assertFalse(outlierRewarded);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Merkle roots
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dataRootShouldBeNonZeroWhenReadingsPresent() {
        List<ReadingSubmissionRequest> readings = List.of(
                reading("0x1111111111111111111111111111111111111111", "0x0882830a1fffffff", 100),
                reading("0x2222222222222222222222222222222222222222", "0x0882830a1fffffff", 100),
                reading("0x3333333333333333333333333333333333333333", "0x0882830a1fffffff", 100)
        );

        EpochAggregate result = aggregator.aggregate(EPOCH_ID, readings);

        assertNotNull(result.dataRoot());
        assertEquals(32, result.dataRoot().length);
        assertFalse(isAllZeros(result.dataRoot()));
    }

    @Test
    void rewardRootShouldBeNonZeroWhenActiveCellExists() {
        List<ReadingSubmissionRequest> readings = List.of(
                reading("0x1111111111111111111111111111111111111111", "0x0882830a1fffffff", 100),
                reading("0x2222222222222222222222222222222222222222", "0x0882830a1fffffff", 100),
                reading("0x3333333333333333333333333333333333333333", "0x0882830a1fffffff", 100)
        );

        EpochAggregate result = aggregator.aggregate(EPOCH_ID, readings);

        assertNotNull(result.rewardRoot());
        assertEquals(32, result.rewardRoot().length);
        assertFalse(isAllZeros(result.rewardRoot()));
    }

    @Test
    void dataRootShouldBeDeterministic() {
        List<ReadingSubmissionRequest> readings = List.of(
                reading("0x1111111111111111111111111111111111111111", "0x0882830a1fffffff", 100),
                reading("0x2222222222222222222222222222222222222222", "0x0882830a1fffffff", 120),
                reading("0x3333333333333333333333333333333333333333", "0x0882830a1fffffff", 110)
        );

        ProtocolEpochAggregator agg2 = new ProtocolEpochAggregator(
                relayProperties(), new UniformStakeWeightProvider(), new InMemoryPenaltyTracker());

        byte[] root1 = aggregator.aggregate(EPOCH_ID, readings).dataRoot();
        byte[] root2 = agg2.aggregate(EPOCH_ID, readings).dataRoot();

        assertArrayEquals(root1, root2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Penalty tracking
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void reporterWithTenConsecutiveZeroScoreEpochsShouldAppearInPenaltyList() {
        InMemoryPenaltyTracker tracker = new InMemoryPenaltyTracker();
        ProtocolEpochAggregator agg = new ProtocolEpochAggregator(
                relayProperties(), new UniformStakeWeightProvider(), tracker);

        // Run THRESHOLD-1 epochs so the counter reaches THRESHOLD-1 without triggering
        for (int i = 0; i < InMemoryPenaltyTracker.THRESHOLD - 1; i++) {
            agg.aggregate(EPOCH_ID + i, List.of(
                    reading("0x1111111111111111111111111111111111111111", "0x0882830a1fffffff", 100),
                    reading("0x2222222222222222222222222222222222222222", "0x0882830a1fffffff", 100),
                    reading("0x3333333333333333333333333333333333333333", "0x0882830a1fffffff", 200)
            ));
        }

        // The THRESHOLD-th zero-score epoch is the trigger epoch
        EpochAggregate result = agg.aggregate(EPOCH_ID + InMemoryPenaltyTracker.THRESHOLD - 1, List.of(
                reading("0x1111111111111111111111111111111111111111", "0x0882830a1fffffff", 100),
                reading("0x2222222222222222222222222222222222222222", "0x0882830a1fffffff", 100),
                reading("0x3333333333333333333333333333333333333333", "0x0882830a1fffffff", 200)
        ));

        assertTrue(result.penaltyList().contains("0x3333333333333333333333333333333333333333"));
    }

    @Test
    void emptyReadingsShouldReturnEmptyAggregate() {
        EpochAggregate result = aggregator.aggregate(EPOCH_ID, List.of());

        assertEquals(0, result.totalReadings());
        assertEquals(0, result.activeCells());
        assertTrue(result.rewards().isEmpty());
        assertTrue(result.penaltyList().isEmpty());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static ReadingSubmissionRequest reading(String reporter, String h3Index, int aqi) {
        return new ReadingSubmissionRequest(
                reporter,
                h3Index,
                EPOCH_ID,
                EPOCH_ID * EPOCH_DURATION + 1,
                aqi,
                200,
                null, null, null, null, null,
                "0x" + "1".repeat(130)
        );
    }

    private static RelayProperties relayProperties() {
        RelayProperties.Messages messages = new RelayProperties.Messages(
                "aqi must be greater than %d",
                "pm25 must be greater than %d",
                "timestamp is required",
                "timestamp is outside allowed tolerance window",
                "epochId does not match timestamp and epoch duration",
                "h3Index must be a 64-bit hex string",
                "h3Index must be valid hex",
                "h3Index resolution must be %d",
                "reporter can submit once every %d seconds"
        );
        RelayProperties.Validation validation = new RelayProperties.Validation(8, 300, 1, 1, messages);
        RelayProperties.Epoch epoch = new RelayProperties.Epoch(EPOCH_DURATION, 60000, 300,
                3, 50, EPOCH_BUDGET);
        RelayProperties.Eip712 eip712 = new RelayProperties.Eip712("VayuProtocol", "1", 84532,
                "0x0000000000000000000000000000000000000000");
        RelayProperties.Security security = new RelayProperties.Security(false, false, eip712);
        return new RelayProperties(epoch, validation, security, null, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Multi-cell scenarios
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void twoActiveCellsShouldBothBeCountedInActiveCells() {
        String cellA = "0x0882830a1fffffff";
        String cellB = "0x0882830b1fffffff";
        List<ReadingSubmissionRequest> readings = List.of(
                reading("0x1111111111111111111111111111111111111111", cellA, 100),
                reading("0x2222222222222222222222222222222222222222", cellA, 100),
                reading("0x3333333333333333333333333333333333333333", cellA, 100),
                reading("0x4444444444444444444444444444444444444444", cellB, 100),
                reading("0x5555555555555555555555555555555555555555", cellB, 100),
                reading("0x6666666666666666666666666666666666666666", cellB, 100)
        );

        EpochAggregate result = aggregator.aggregate(EPOCH_ID, readings);

        assertEquals(2, result.activeCells());
        assertEquals(2, result.cells().size());
        assertTrue(result.cells().stream().allMatch(CellAggregate::active));
    }

    @Test
    void rewardBudgetShouldBeSplitAcrossTwoActiveCells() {
        String cellA = "0x0882830a1fffffff";
        String cellB = "0x0882830b1fffffff";
        // All reporters score 1.0 (AQI = cell median exactly)
        List<ReadingSubmissionRequest> readings = List.of(
                reading("0x1111111111111111111111111111111111111111", cellA, 100),
                reading("0x2222222222222222222222222222222222222222", cellA, 100),
                reading("0x3333333333333333333333333333333333333333", cellA, 100),
                reading("0x4444444444444444444444444444444444444444", cellB, 200),
                reading("0x5555555555555555555555555555555555555555", cellB, 200),
                reading("0x6666666666666666666666666666666666666666", cellB, 200)
        );

        EpochAggregate result = aggregator.aggregate(EPOCH_ID, readings);

        // 6 rewards (3 per active cell)
        assertEquals(6, result.rewards().size());

        // Total rewards must not exceed the 98% reward budget
        BigInteger rewardBudget = EPOCH_BUDGET
                .multiply(BigInteger.valueOf(9800))
                .divide(BigInteger.valueOf(10000));
        BigInteger total = result.rewards().stream()
                .map(ReporterReward::amount)
                .reduce(BigInteger.ZERO, BigInteger::add);
        assertTrue(total.compareTo(rewardBudget) <= 0,
                "Total rewards " + total + " must not exceed reward budget " + rewardBudget);
        // Rewards should consume at least 99% of budget (checking no rounding leak)
        BigInteger lowerBound = rewardBudget.multiply(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100));
        assertTrue(total.compareTo(lowerBound) >= 0,
                "Total rewards " + total + " unexpectedly far below budget");
    }

    @Test
    void mixedActiveCellAndInactiveCellShouldOnlyCountActiveOne() {
        String activeCell   = "0x0882830a1fffffff";  // 3 reporters → active
        String inactiveCell = "0x0882830b1fffffff";  // 2 reporters → inactive
        List<ReadingSubmissionRequest> readings = List.of(
                reading("0x1111111111111111111111111111111111111111", activeCell, 100),
                reading("0x2222222222222222222222222222222222222222", activeCell, 100),
                reading("0x3333333333333333333333333333333333333333", activeCell, 100),
                reading("0x4444444444444444444444444444444444444444", inactiveCell, 100),
                reading("0x5555555555555555555555555555555555555555", inactiveCell, 100)
        );

        EpochAggregate result = aggregator.aggregate(EPOCH_ID, readings);

        assertEquals(1, result.activeCells());
        assertEquals(3, result.rewards().size());  // only active cell reporters rewarded
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reading and reporter counts
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void totalReadingsAndUniqueReporterCountsShouldBeCorrect() {
        String cellA = "0x0882830a1fffffff";
        String cellB = "0x0882830b1fffffff";
        String r1 = "0x1111111111111111111111111111111111111111";
        // R1 appears in both cells; R2-R5 appear once each
        List<ReadingSubmissionRequest> readings = List.of(
                reading(r1, cellA, 100),
                reading("0x2222222222222222222222222222222222222222", cellA, 100),
                reading("0x3333333333333333333333333333333333333333", cellA, 100),
                reading(r1, cellB, 100),
                reading("0x4444444444444444444444444444444444444444", cellB, 100)
        );

        EpochAggregate result = aggregator.aggregate(EPOCH_ID, readings);

        assertEquals(5, result.totalReadings());
        // R1 appears twice but is counted once as unique reporter
        assertEquals(4, result.uniqueReporters());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Zero-score edge cases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void activeCellWhereAllReportersScoreZeroShouldProduceNoRewards() {
        // 4 reporters: [100, 100, 200, 200] → median = (100+200)/2 = 150
        // Each reporter is exactly 50 from median (= tolerance) → score = max(0, 1-1) = 0
        List<ReadingSubmissionRequest> readings = List.of(
                reading("0x1111111111111111111111111111111111111111", "0x0882830a1fffffff", 100),
                reading("0x2222222222222222222222222222222222222222", "0x0882830a1fffffff", 100),
                reading("0x3333333333333333333333333333333333333333", "0x0882830a1fffffff", 200),
                reading("0x4444444444444444444444444444444444444444", "0x0882830a1fffffff", 200)
        );

        EpochAggregate result = aggregator.aggregate(EPOCH_ID, readings);

        assertEquals(1, result.activeCells());
        assertTrue(result.rewards().isEmpty(),
                "No rewards expected when all reporters score exactly zero");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inactive cell reporters and penalty tracking
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void inactiveCellReportersShouldNeverAccumulatePenaltyStreak() {
        // Only 2 reporters in the cell — cell stays inactive every epoch
        String inactiveCell = "0x0882830b1fffffff";
        List<ReadingSubmissionRequest> readings = List.of(
                reading("0x1111111111111111111111111111111111111111", inactiveCell, 50),
                reading("0x2222222222222222222222222222222222222222", inactiveCell, 50)
        );

        // Run well past the penalty threshold
        EpochAggregate last = null;
        for (int i = 0; i <= InMemoryPenaltyTracker.THRESHOLD + 1; i++) {
            last = aggregator.aggregate(EPOCH_ID + i, readings);
        }

        assertTrue(Objects.requireNonNull(last).penaltyList().isEmpty(),
                "Reporters in inactive cells must not accumulate a zero-score streak");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Static helper: median edge cases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void medianOfSingleElementShouldReturnThatElement() {
        assertEquals(42, ProtocolEpochAggregator.median(new int[]{42}));
    }

    @Test
    void medianShouldBeStableRegardlessOfInputOrder() {
        // [300, 100, 200] and [100, 200, 300] should yield the same median (200)
        assertEquals(
                ProtocolEpochAggregator.median(new int[]{100, 200, 300}),
                ProtocolEpochAggregator.median(new int[]{300, 100, 200})
        );
    }

    private static boolean isAllZeros(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) return false;
        }
        return true;
    }
}
