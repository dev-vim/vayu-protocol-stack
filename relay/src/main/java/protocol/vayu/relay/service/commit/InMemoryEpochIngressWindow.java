package protocol.vayu.relay.service.commit;

import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
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

    /**
     * Atomically records {@code replayKey} for the reading's epoch and enqueues
     * the reading only if the key had not been seen before.
     *
     * <p>The {@link Set#add} on a {@link ConcurrentHashMap#newKeySet()} is
     * atomic, so exactly one concurrent caller wins when two threads race with
     * the same key.
     */
    @Override
    public boolean enqueueIfNotSeen(ReadingSubmissionRequest request, String replayKey) {
        Set<String> seen = seenKeysByEpoch.computeIfAbsent(
                request.epochId(), ignored -> ConcurrentHashMap.newKeySet());

        if (!seen.add(replayKey)) {
            return false;
        }

        readingsByEpoch
                .computeIfAbsent(request.epochId(), ignored -> new ConcurrentLinkedQueue<>())
                .add(request);
        return true;
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
