package protocol.vayu.relay.service.commit;

import protocol.vayu.relay.service.commit.aggregation.EpochAggregate;

public interface EpochCommitPublisher {

    CommitPublication publish(EpochAggregate aggregate);
}
