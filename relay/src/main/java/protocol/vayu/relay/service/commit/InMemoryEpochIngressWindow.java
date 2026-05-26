package protocol.vayu.relay.service.commit;

import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryEpochIngressWindow implements EpochIngressWindow {

    private final ConcurrentMap<Long, ConcurrentLinkedQueue<ReadingSubmissionRequest>> readingsByEpoch =
            new ConcurrentHashMap<>();

    private final ConcurrentMap<Long, Set<String>> seenKeysByEpoch = new ConcurrentHashMap<>();

    public InMemoryEpochIngressWindow(MeterRegistry registry) {
        Gauge.builder("vayu.ingress.pending", this, InMemoryEpochIngressWindow::pendingReadings)
                .description("Number of readings currently buffered across all open epoch windows")
                .register(registry);
    }

    /**
     * Atomically claims {@code replayKey} for the reading's epoch without enqueuing it.
     * The first caller for a given key wins; all subsequent callers for the same key
     * receive {@code false}.
     */
    @Override
    public boolean tryClaimReplayKey(long epochId, String replayKey) {
        Set<String> seen = seenKeysByEpoch.computeIfAbsent(
                epochId, ignored -> ConcurrentHashMap.newKeySet());
        return seen.add(replayKey);
    }

    /**
     * Releases a previously claimed {@code replayKey} so the reporter can retry.
     */
    @Override
    public void releaseReplayKey(long epochId, String replayKey) {
        Set<String> seen = seenKeysByEpoch.get(epochId);
        if (seen != null) {
            seen.remove(replayKey);
        }
    }

    /** Plain enqueue without dedup — used by {@link EpochReadingStore} consumers (e.g. tests). */
    @Override
    public void enqueue(ReadingSubmissionRequest request) {
        readingsByEpoch
                .computeIfAbsent(request.epochId(), ignored -> new ConcurrentLinkedQueue<>())
                .add(request);
    }

    /** Drains the reading queue and clears the seen-key set for {@code epochId} in one call. */
    @Override
    public List<ReadingSubmissionRequest> drainEpoch(long epochId) {
        seenKeysByEpoch.remove(epochId);
        ConcurrentLinkedQueue<ReadingSubmissionRequest> drained = readingsByEpoch.remove(epochId);
        if (drained == null || drained.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(drained);
    }

    @Override
    public int pendingReadings() {
        return readingsByEpoch.values().stream().mapToInt(ConcurrentLinkedQueue::size).sum();
    }
}
