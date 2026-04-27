package protocol.vayu.relay.service.commit;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class CommitCycleState {

    private final AtomicLong lastWorkerHeartbeat = new AtomicLong(0);
    private final AtomicLong lastCommittedEpoch = new AtomicLong(-1);
    private final AtomicLong lastCommittedAt = new AtomicLong(0);
    private final AtomicReference<String> lastCommitTxHash = new AtomicReference<>("");
    private final AtomicReference<String> lastFailureReason = new AtomicReference<>("");

    public void markWorkerHeartbeat(long nowEpochSeconds) {
        lastWorkerHeartbeat.set(nowEpochSeconds);
    }

    public void recordCommitted(CommitPublication publication) {
        lastCommittedEpoch.set(publication.epochId());
        lastCommittedAt.set(publication.submittedAt());
        lastCommitTxHash.set(publication.txHash());
        lastFailureReason.set("");
    }

    public void recordEmptyEpoch(long epochId, long nowEpochSeconds) {
        lastCommittedEpoch.set(epochId);
        lastCommittedAt.set(nowEpochSeconds);
        lastFailureReason.set("");
    }

    public void recordFailure(String reason) {
        lastFailureReason.set(reason == null ? "" : reason);
    }

    public long lastWorkerHeartbeat() {
        return lastWorkerHeartbeat.get();
    }

    public long lastCommittedEpoch() {
        return lastCommittedEpoch.get();
    }

    public long lastCommittedAt() {
        return lastCommittedAt.get();
    }

    public String lastCommitTxHash() {
        return lastCommitTxHash.get();
    }

    public String lastFailureReason() {
        return lastFailureReason.get();
    }

    public boolean isWorkerRunning(long nowEpochSeconds, long heartbeatTimeoutSeconds) {
        long heartbeat = lastWorkerHeartbeat.get();
        if (heartbeat <= 0) {
            return false;
        }
        return (nowEpochSeconds - heartbeat) <= Math.max(1, heartbeatTimeoutSeconds);
    }
}
