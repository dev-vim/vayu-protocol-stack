package protocol.vayu.relay.service.commit.aggregation;

import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import protocol.vayu.relay.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Protocol-compliant EpochAggregator that implements the full epoch commit pipeline
 * as specified in docs/sequence-diagrams.md and VayuTypes.sol.
 *
 * Per-epoch steps:
 *   1. Group readings by H3 cell.
 *   2. Mark cells active when distinct reporter count >= minReportersPerCell (protocol: 3).
 *   3. Compute median AQI per active cell (resistant to outlier manipulation).
 *   4. Score each reporter: score = max(0, 1 − |aqi − median| / scoringTolerance).
 *   5. Distribute cell budget (epochBudget × 98% / activeCells) by score × stake weight.
 *   6. Build DATA Merkle root (leaves sorted by keccak256(reporter, h3Index)).
 *   7. Build REWARD Merkle root (leaves sorted by reporter address).
 *   8. Derive penalty list via PenaltyTracker (≥10 consecutive zero-score epochs).
 *
 * Relay fee is 2% (RELAY_FEE_BPS = 200) — matches VayuTypes.RELAY_FEE_BPS.
 */
@Primary
@Component
public class ProtocolEpochAggregator implements EpochAggregator {

    private static final Logger LOG = LoggerFactory.getLogger(ProtocolEpochAggregator.class);

    /** VayuTypes.RELAY_FEE_BPS = 200 (2%). */
    private static final int RELAY_FEE_BPS = 200;
    private static final int BPS_DENOMINATOR = 10_000;

    /** Fixed-point precision multiplier for score arithmetic (avoids floating-point in BigInteger math). */
    private static final long SCORE_PRECISION = 1_000_000L;

    private final int minReportersPerCell;
    private final int scoringToleranceAqi;
    private final BigInteger epochBudgetWei;
    private final StakeWeightProvider stakeWeightProvider;
    private final PenaltyTracker penaltyTracker;

    public ProtocolEpochAggregator(
            RelayProperties relayProperties,
            StakeWeightProvider stakeWeightProvider,
            PenaltyTracker penaltyTracker
    ) {
        this.minReportersPerCell = relayProperties.epoch().minReportersPerCell();
        this.scoringToleranceAqi = Math.max(1, relayProperties.epoch().scoringToleranceAqi());
        this.epochBudgetWei = relayProperties.epoch().epochBudgetWei();
        this.stakeWeightProvider = stakeWeightProvider;
        this.penaltyTracker = penaltyTracker;
    }

    @Override
    public EpochAggregate aggregate(long epochId, List<ReadingSubmissionRequest> readings) {
        if (readings == null || readings.isEmpty()) {
            return empty(epochId);
        }

        // ── 1. Group by cell ──────────────────────────────────────────────────
        Map<String, List<ReadingSubmissionRequest>> byCell = new LinkedHashMap<>();
        Set<String> uniqueReporters = new LinkedHashSet<>();
        for (ReadingSubmissionRequest r : readings) {
            uniqueReporters.add(r.reporter());
            byCell.computeIfAbsent(r.h3Index(), ignored -> new ArrayList<>()).add(r);
        }

        // ── 2-4. Aggregate each cell ──────────────────────────────────────────
        List<CellAggregate> cells = new ArrayList<>();
        int activeCells = 0;
        Map<String, Double> allReporterScores = new HashMap<>();  // for penalty tracker

        for (Map.Entry<String, List<ReadingSubmissionRequest>> entry : byCell.entrySet()) {
            String h3Index = entry.getKey();
            List<ReadingSubmissionRequest> cellReadings = entry.getValue();

            Set<String> distinctReporters = new LinkedHashSet<>();
            cellReadings.forEach(r -> distinctReporters.add(r.reporter()));
            boolean active = distinctReporters.size() >= minReportersPerCell;
            if (active) activeCells++;

            int medianAqi = median(cellReadings.stream().mapToInt(ReadingSubmissionRequest::aqi).toArray());

            List<ReporterScore> scores = active
                    ? scoreReporters(cellReadings, medianAqi)
                    : List.of();

            scores.forEach(s -> allReporterScores.merge(s.reporter(), s.score(),
                    (existing, newScore) -> Math.max(existing, newScore)));

            cells.add(new CellAggregate(
                    h3Index,
                    cellReadings.size(),
                    active,
                    medianAqi,
                    avgInt(cellReadings, r -> r.pm25()),
                    avgInt(cellReadings, r -> orZero(r.pm10())),
                    avgInt(cellReadings, r -> orZero(r.o3())),
                    avgInt(cellReadings, r -> orZero(r.no2())),
                    avgInt(cellReadings, r -> orZero(r.so2())),
                    avgInt(cellReadings, r -> orZero(r.co())),
                    scores
            ));
        }

        // ── 5. Reward allocation ──────────────────────────────────────────────
        List<ReporterReward> rewards = activeCells > 0
                ? allocateRewards(epochId, cells, activeCells)
                : List.of();

        // ── 6-7. Merkle roots ─────────────────────────────────────────────────
        byte[] dataRoot   = EpochMerkleBuilder.buildDataRoot(readings);
        byte[] rewardRoot = EpochMerkleBuilder.buildRewardRoot(rewards, epochId);

        // ── 8. Penalty list ───────────────────────────────────────────────────
        penaltyTracker.recordScores(epochId, allReporterScores);
        List<String> penaltyList = penaltyTracker.penaltyList(epochId);

        LOG.debug("epoch {} aggregated: totalReadings={}, activeCells={}/{}, rewards={}, penalty={}",
                epochId, readings.size(), activeCells, cells.size(), rewards.size(), penaltyList.size());

        return new EpochAggregate(
                epochId,
                readings.size(),
                uniqueReporters.size(),
                cells,
                activeCells,
                rewards,
                dataRoot,
                rewardRoot,
                List.copyOf(readings),
                penaltyList
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scoring
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Assigns one score per distinct reporter in this cell.
     * When a reporter submitted multiple readings, their representative AQI
     * is the mean of those readings.
     * score = max(0, 1 − |repAqi − medianAqi| / scoringTolerance)
     */
    private List<ReporterScore> scoreReporters(List<ReadingSubmissionRequest> cellReadings, int medianAqi) {
        // Aggregate per-reporter mean AQI within this cell
        Map<String, long[]> perReporter = new LinkedHashMap<>();
        for (ReadingSubmissionRequest r : cellReadings) {
            perReporter.computeIfAbsent(r.reporter(), ignored -> new long[]{0, 0});
            long[] acc = perReporter.get(r.reporter());
            acc[0] += r.aqi();  // sum
            acc[1]++;           // count
        }

        List<ReporterScore> scores = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : perReporter.entrySet()) {
            int repAqi = (int) Math.round((double) entry.getValue()[0] / entry.getValue()[1]);
            double score = Math.max(0.0,
                    1.0 - (double) Math.abs(repAqi - medianAqi) / scoringToleranceAqi);
            scores.add(new ReporterScore(entry.getKey(), score));
        }
        return scores;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reward allocation
    // ─────────────────────────────────────────────────────────────────────────

    private List<ReporterReward> allocateRewards(long epochId, List<CellAggregate> cells, int activeCells) {
        // rewardBudget = epochBudget * (1 - RELAY_FEE_BPS / BPS_DENOMINATOR)
        BigInteger rewardBudget = epochBudgetWei
                .multiply(BigInteger.valueOf(BPS_DENOMINATOR - RELAY_FEE_BPS))
                .divide(BigInteger.valueOf(BPS_DENOMINATOR));

        BigInteger cellBudget = rewardBudget.divide(BigInteger.valueOf(activeCells));

        List<ReporterReward> rewards = new ArrayList<>();
        for (CellAggregate cell : cells) {
            if (!cell.active() || cell.reporterScores().isEmpty()) continue;

            long h3IndexLong = EpochMerkleBuilder.parseH3Index(cell.h3Index());

            // Weighted reward: weight_i = scoreFixed_i * stake_i
            Map<String, BigInteger> weightByReporter = new LinkedHashMap<>();
            BigInteger totalWeight = BigInteger.ZERO;
            for (ReporterScore rs : cell.reporterScores()) {
                long scoreFixed = Math.round(rs.score() * SCORE_PRECISION);
                if (scoreFixed <= 0) continue;
                BigInteger stake = stakeWeightProvider.stakeOf(rs.reporter());
                BigInteger weight = BigInteger.valueOf(scoreFixed).multiply(stake);
                weightByReporter.put(rs.reporter(), weight);
                totalWeight = totalWeight.add(weight);
            }

            if (totalWeight.compareTo(BigInteger.ZERO) == 0) continue;

            for (Map.Entry<String, BigInteger> entry : weightByReporter.entrySet()) {
                BigInteger amount = cellBudget
                        .multiply(entry.getValue())
                        .divide(totalWeight);
                if (amount.compareTo(BigInteger.ZERO) > 0) {
                    rewards.add(new ReporterReward(entry.getKey(), h3IndexLong, amount));
                }
            }
        }
        return rewards;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static EpochAggregate empty(long epochId) {
        return new EpochAggregate(epochId, 0, 0, List.of(), 0, List.of(), null, null, List.of(), List.of());
    }

    /** Median of a non-empty integer array. Uses lower-middle for even-count sets. */
    static int median(int[] values) {
        int[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        if (n % 2 == 1) {
            return sorted[n / 2];
        }
        return (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    @FunctionalInterface
    private interface ToInt {
        int apply(ReadingSubmissionRequest r);
    }

    private static int avgInt(List<ReadingSubmissionRequest> readings, ToInt fn) {
        return (int) Math.round(
                readings.stream().mapToInt(fn::apply).average().orElse(0));
    }

    private static int orZero(Integer v) {
        return v == null ? 0 : v;
    }
}
