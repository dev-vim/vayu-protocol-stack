package protocol.vayu.relay.service.commit;

import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;

import java.util.List;

public interface EpochReadingStore {

    void enqueue(ReadingSubmissionRequest request);

    List<ReadingSubmissionRequest> drainEpoch(long epochId);

    int pendingReadings();
}
