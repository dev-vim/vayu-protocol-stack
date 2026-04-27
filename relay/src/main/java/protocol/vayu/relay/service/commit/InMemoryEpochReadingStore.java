package protocol.vayu.relay.service.commit;

import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryEpochReadingStore implements EpochReadingStore {

    private final ConcurrentMap<Long, ConcurrentLinkedQueue<ReadingSubmissionRequest>> readingsByEpoch =
            new ConcurrentHashMap<>();

    @Override
    public void enqueue(ReadingSubmissionRequest request) {
        readingsByEpoch
                .computeIfAbsent(request.epochId(), ignored -> new ConcurrentLinkedQueue<>())
                .add(request);
    }

    @Override
    public List<ReadingSubmissionRequest> drainEpoch(long epochId) {
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
