package protocol.vayu.relay.service.commit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommitCycleStateTest {

    private final CommitCycleState state = new CommitCycleState();

    @Test
    void initialStateShouldHaveNegativeLastCommittedEpochAndEmptyStrings() {
        assertEquals(-1, state.lastCommittedEpoch());
        assertEquals("", state.lastCommitTxHash());
        assertEquals("", state.lastFailureReason());
        assertEquals(0, state.lastWorkerHeartbeat());
        assertEquals(0, state.lastCommittedAt());
    }

    @Test
    void recordCommittedShouldUpdateEpochTxHashAndClearFailure() {
        state.recordFailure("previous error");

        state.recordCommitted(new CommitPublication(5L, "0xdeadbeef", 12, 1000L));

        assertEquals(5L, state.lastCommittedEpoch());
        assertEquals("0xdeadbeef", state.lastCommitTxHash());
        assertEquals(1000L, state.lastCommittedAt());
        assertEquals("", state.lastFailureReason());
    }

    @Test
    void recordEmptyEpochShouldAdvanceWatermarkAndClearFailure() {
        state.recordFailure("previous error");

        state.recordEmptyEpoch(7L, 9999L);

        assertEquals(7L, state.lastCommittedEpoch());
        assertEquals(9999L, state.lastCommittedAt());
        assertEquals("", state.lastFailureReason());
    }

    @Test
    void recordFailureShouldSetReasonWithoutChangingCommittedEpoch() {
        state.recordCommitted(new CommitPublication(3L, "0xfoo", 1, 100L));

        state.recordFailure("connection refused");

        assertEquals(3L, state.lastCommittedEpoch());
        assertEquals("connection refused", state.lastFailureReason());
    }

    @Test
    void recordFailureShouldTreatNullAsEmptyString() {
        state.recordFailure(null);

        assertEquals("", state.lastFailureReason());
    }

    @Test
    void markWorkerHeartbeatShouldUpdateTimestamp() {
        state.markWorkerHeartbeat(42000L);

        assertEquals(42000L, state.lastWorkerHeartbeat());
    }

    @Test
    void isWorkerRunningShouldReturnFalseWhenNoHeartbeatSet() {
        assertFalse(state.isWorkerRunning(1000L, 60L));
    }

    @Test
    void isWorkerRunningShouldReturnFalseWhenHeartbeatExpired() {
        state.markWorkerHeartbeat(900L);

        // 100s ago, timeout is 60s
        assertFalse(state.isWorkerRunning(1000L, 60L));
    }

    @Test
    void isWorkerRunningShouldReturnTrueWhenHeartbeatWithinWindow() {
        state.markWorkerHeartbeat(980L);

        // 20s ago, timeout is 60s
        assertTrue(state.isWorkerRunning(1000L, 60L));
    }

    @Test
    void isWorkerRunningShouldReturnTrueAtExactBoundary() {
        state.markWorkerHeartbeat(940L);

        // exactly 60s ago, timeout is 60s — (1000 - 940) == 60 <= 60
        assertTrue(state.isWorkerRunning(1000L, 60L));
    }
}
