package protocol.vayu.relay.service.commit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Hash;
import protocol.vayu.relay.service.commit.aggregation.EpochAggregate;

import java.time.Instant;

@Component
public class LoggingEpochCommitPublisher implements EpochCommitPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingEpochCommitPublisher.class);

    @Override
    public CommitPublication publish(EpochAggregate aggregate) {
        long submittedAt = Instant.now().getEpochSecond();

        String dataRootHex  = aggregate.dataRoot()   != null
                ? org.web3j.utils.Numeric.toHexString(aggregate.dataRoot())   : "(none)";
        String rewardRootHex = aggregate.rewardRoot() != null
                ? org.web3j.utils.Numeric.toHexString(aggregate.rewardRoot()) : "(none)";

        // Produce a deterministic stub tx-hash from the aggregate metadata.
        // Replace with a real web3j contract call in the production publisher.
        String payload = aggregate.epochId() + ":" + aggregate.totalReadings() + ":"
                + aggregate.uniqueReporters() + ":" + aggregate.cells().size() + ":" + submittedAt;
        String txHash = Hash.sha3String(payload);

        LOG.info(
                "epoch commit published: epochId={}, totalReadings={}, activeCells={}/{}, " +
                "rewards={}, penalty={}, dataRoot={}, rewardRoot={}, txHash={}",
                aggregate.epochId(),
                aggregate.totalReadings(),
                aggregate.activeCells(),
                aggregate.cells().size(),
                aggregate.rewards().size(),
                aggregate.penaltyList().size(),
                dataRootHex,
                rewardRootHex,
                txHash
        );

        return new CommitPublication(aggregate.epochId(), txHash, aggregate.totalReadings(), submittedAt);
    }
}
