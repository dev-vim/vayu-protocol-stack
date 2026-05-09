package protocol.vayu.relay.service.commit.aggregation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryPenaltyTrackerTest {

    private static final String R1 = "0x1111111111111111111111111111111111111111";
    private static final String R2 = "0x2222222222222222222222222222222222222222";

    private InMemoryPenaltyTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new InMemoryPenaltyTracker();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Initial state
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void initiallyNoPenalties() {
        assertTrue(tracker.penaltyList(1L).isEmpty());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Threshold boundary
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void singleZeroScoreEpochShouldNotTriggerPenalty() {
        tracker.recordScores(1L, Map.of(R1, 0.0));

        assertTrue(tracker.penaltyList(1L).isEmpty());
    }

    @Test
    void nineConsecutiveZeroScoresShouldNotTriggerPenalty() {
        for (long epoch = 1; epoch < InMemoryPenaltyTracker.THRESHOLD; epoch++) {
            tracker.recordScores(epoch, Map.of(R1, 0.0));
        }

        assertTrue(tracker.penaltyList(InMemoryPenaltyTracker.THRESHOLD).isEmpty());
    }

    @Test
    void exactlyThresholdConsecutiveZerosShouldTriggerPenalty() {
        for (long epoch = 1; epoch <= InMemoryPenaltyTracker.THRESHOLD; epoch++) {
            tracker.recordScores(epoch, Map.of(R1, 0.0));
        }

        List<String> penalty = tracker.penaltyList(InMemoryPenaltyTracker.THRESHOLD);
        assertEquals(1, penalty.size());
        assertTrue(penalty.contains(R1));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Streak reset on positive score
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void positiveScoreAfterNineZerosShouldResetStreak() {
        // Nine consecutive zeros
        for (long epoch = 1; epoch < InMemoryPenaltyTracker.THRESHOLD; epoch++) {
            tracker.recordScores(epoch, Map.of(R1, 0.0));
        }
        // Positive score resets streak
        tracker.recordScores(InMemoryPenaltyTracker.THRESHOLD, Map.of(R1, 0.5));
        // Nine more zeros (only 9 since the positive score, not 18 total)
        for (long epoch = InMemoryPenaltyTracker.THRESHOLD + 1;
                epoch <= InMemoryPenaltyTracker.THRESHOLD * 2 - 1;
                epoch++) {
            tracker.recordScores(epoch, Map.of(R1, 0.0));
        }

        // Only 9 consecutive zeros since the last positive score — should not be penalised
        assertTrue(tracker.penaltyList(InMemoryPenaltyTracker.THRESHOLD * 2).isEmpty());
    }

    @Test
    void positiveScoreAtExactlyThresholdShouldPreventPenalty() {
        // Positive score on the very epoch that would have been the 10th zero
        for (long epoch = 1; epoch < InMemoryPenaltyTracker.THRESHOLD; epoch++) {
            tracker.recordScores(epoch, Map.of(R1, 0.0));
        }
        // Instead of zero, reporter scores positively
        tracker.recordScores(InMemoryPenaltyTracker.THRESHOLD, Map.of(R1, 1.0));

        assertTrue(tracker.penaltyList(InMemoryPenaltyTracker.THRESHOLD).isEmpty());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Counter reset after penalty
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void penaltyListShouldResetCounterAfterFlagging() {
        for (long epoch = 1; epoch <= InMemoryPenaltyTracker.THRESHOLD; epoch++) {
            tracker.recordScores(epoch, Map.of(R1, 0.0));
        }
        // First call — reporter is penalised and counter reset
        List<String> first = tracker.penaltyList(InMemoryPenaltyTracker.THRESHOLD);
        assertEquals(1, first.size());

        // Calling again without new zero-score epochs — counter was reset, reporter should not appear
        List<String> second = tracker.penaltyList(InMemoryPenaltyTracker.THRESHOLD);
        assertTrue(second.isEmpty());
    }

    @Test
    void reporterRequiresTenMoreZerosAfterBeingPenalised() {
        // First penalisation
        for (long epoch = 1; epoch <= InMemoryPenaltyTracker.THRESHOLD; epoch++) {
            tracker.recordScores(epoch, Map.of(R1, 0.0));
        }
        tracker.penaltyList(InMemoryPenaltyTracker.THRESHOLD);

        // Nine more zeros after reset — should NOT trigger again
        for (long epoch = InMemoryPenaltyTracker.THRESHOLD + 1;
                epoch <= InMemoryPenaltyTracker.THRESHOLD * 2 - 1;
                epoch++) {
            tracker.recordScores(epoch, Map.of(R1, 0.0));
        }
        assertTrue(tracker.penaltyList(InMemoryPenaltyTracker.THRESHOLD * 2 - 1).isEmpty());

        // Tenth zero after reset — should trigger again
        tracker.recordScores(InMemoryPenaltyTracker.THRESHOLD * 2L, Map.of(R1, 0.0));
        List<String> second = tracker.penaltyList(InMemoryPenaltyTracker.THRESHOLD * 2L);
        assertTrue(second.contains(R1));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Multiple reporters
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void multipleReportersTrackedIndependently() {
        // R1 reaches threshold; R2 only halfway
        for (long epoch = 1; epoch <= InMemoryPenaltyTracker.THRESHOLD; epoch++) {
            tracker.recordScores(epoch, Map.of(R1, 0.0));
        }
        for (long epoch = 1; epoch <= InMemoryPenaltyTracker.THRESHOLD / 2; epoch++) {
            tracker.recordScores(epoch, Map.of(R2, 0.0));
        }

        List<String> penalty = tracker.penaltyList(InMemoryPenaltyTracker.THRESHOLD);
        assertEquals(1, penalty.size());
        assertTrue(penalty.contains(R1));
        assertFalse(penalty.contains(R2));
    }

    @Test
    void reporterNeverRecordedShouldNeverAppearInPenalties() {
        for (long epoch = 1; epoch <= InMemoryPenaltyTracker.THRESHOLD; epoch++) {
            tracker.recordScores(epoch, Map.of(R2, 0.0));
        }

        // R1 was never scored — must not appear in any penalty list
        assertFalse(tracker.penaltyList(InMemoryPenaltyTracker.THRESHOLD).contains(R1));
    }

    @Test
    void zeroScoreAndPositiveScoreInSameEpochShouldBeTrackedPerReporter() {
        // R1 scores zero, R2 scores positive in every epoch up to threshold
        for (long epoch = 1; epoch <= InMemoryPenaltyTracker.THRESHOLD; epoch++) {
            tracker.recordScores(epoch, Map.of(R1, 0.0, R2, 1.0));
        }

        List<String> penalty = tracker.penaltyList(InMemoryPenaltyTracker.THRESHOLD);
        assertTrue(penalty.contains(R1));
        assertFalse(penalty.contains(R2));
    }
}
