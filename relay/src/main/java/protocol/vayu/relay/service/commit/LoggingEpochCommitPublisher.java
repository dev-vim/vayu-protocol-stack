package protocol.vayu.relay.service.commit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Hash;
import protocol.vayu.relay.service.commit.aggregation.EpochAggregate;
import protocol.vayu.relay.service.commit.ipfs.EpochBlobAssembler;
import protocol.vayu.relay.service.commit.ipfs.IpfsPinClient;

import java.time.Instant;

@Component
public class LoggingEpochCommitPublisher implements EpochCommitPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingEpochCommitPublisher.class);

    private final EpochBlobAssembler blobAssembler;
    private final IpfsPinClient ipfsPinClient;

    public LoggingEpochCommitPublisher(EpochBlobAssembler blobAssembler, IpfsPinClient ipfsPinClient) {
        this.blobAssembler = blobAssembler;
        this.ipfsPinClient = ipfsPinClient;
    }

    @Override
    public CommitPublication publish(EpochAggregate aggregate) {
        long submittedAt = Instant.now().getEpochSecond();

        String dataRootHex  = aggregate.dataRoot()   != null
                ? org.web3j.utils.Numeric.toHexString(aggregate.dataRoot())   : "(none)";
        String rewardRootHex = aggregate.rewardRoot() != null
                ? org.web3j.utils.Numeric.toHexString(aggregate.rewardRoot()) : "(none)";

        String jsonBlob = blobAssembler.assemble(aggregate);
        String ipfsCid = ipfsPinClient.pin(aggregate.epochId(), jsonBlob);
        LOG.info("epoch {} blob pinned to IPFS: cid={}", aggregate.epochId(), ipfsCid);

        // Produce a deterministic stub tx-hash from the aggregate metadata.
        // Replace with a real web3j contract call in the production publisher.
        String payload = aggregate.epochId() + ":" + aggregate.totalReadings() + ":"
                + aggregate.uniqueReporters() + ":" + aggregate.cells().size() + ":" + submittedAt;
        String txHash = Hash.sha3String(payload);

        LOG.info(
                "epoch commit published: epochId={}, totalReadings={}, activeCells={}/{}, " +
                "rewards={}, penalty={}, dataRoot={}, rewardRoot={}, ipfsCid={}, txHash={}",
                aggregate.epochId(),
                aggregate.totalReadings(),
                aggregate.activeCells(),
                aggregate.cells().size(),
                aggregate.rewards().size(),
                aggregate.penaltyList().size(),
                dataRootHex,
                rewardRootHex,
                ipfsCid,
                txHash
        );

        return new CommitPublication(aggregate.epochId(), txHash, ipfsCid, aggregate.totalReadings(), submittedAt);
    }
}
