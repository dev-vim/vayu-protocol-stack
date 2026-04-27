package protocol.vayu.relay.service.commit;

public interface EpochCommitPublisher {

    CommitPublication publish(EpochAggregate aggregate);
}
