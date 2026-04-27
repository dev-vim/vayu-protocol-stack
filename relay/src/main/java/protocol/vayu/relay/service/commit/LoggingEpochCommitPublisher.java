package protocol.vayu.relay.service.commit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Hash;

import java.time.Instant;

@Component
public class LoggingEpochCommitPublisher implements EpochCommitPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingEpochCommitPublisher.class);

    @Override
    public CommitPublication publish(EpochAggregate aggregate) {
        long submittedAt = Instant.now().getEpochSecond();
        String payload = aggregate.epochId() + ":" + aggregate.totalReadings() + ":" + aggregate.uniqueReporters()
                + ":" + aggregate.cells().size() + ":" + submittedAt;
        String txHash = Hash.sha3String(payload);

        LOG.info(
                "epoch commit published: epochId={}, totalReadings={}, uniqueReporters={}, cells={}, txHash={}",
                aggregate.epochId(),
                aggregate.totalReadings(),
                aggregate.uniqueReporters(),
                aggregate.cells().size(),
                txHash
        );

        return new CommitPublication(aggregate.epochId(), txHash, aggregate.totalReadings(), submittedAt);
    }
}
