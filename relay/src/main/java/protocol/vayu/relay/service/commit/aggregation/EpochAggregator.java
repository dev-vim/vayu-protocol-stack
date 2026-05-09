package protocol.vayu.relay.service.commit.aggregation;

import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;

import java.util.List;

public interface EpochAggregator {

    EpochAggregate aggregate(long epochId, List<ReadingSubmissionRequest> readings);
}
