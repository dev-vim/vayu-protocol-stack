package protocol.vayu.relay.service.commit;

import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;

import java.util.List;

public interface EpochAggregator {

    EpochAggregate aggregate(long epochId, List<ReadingSubmissionRequest> readings);
}
