package protocol.vayu.relay.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import protocol.vayu.relay.service.commit.EpochCommitPublisher;
import protocol.vayu.relay.service.commit.LoggingEpochCommitPublisher;
import protocol.vayu.relay.service.commit.ipfs.EpochBlobAssembler;
import protocol.vayu.relay.service.commit.ipfs.IpfsPinClient;

/**
 * Selects the active {@link EpochCommitPublisher} implementation based on
 * {@code relay.chain.on-chain-commit-enabled}.
 *
 *   on-chain-commit-enabled=false  → {@link LoggingEpochCommitPublisher}  (default; dev/CI)
 *   on-chain-commit-enabled=true   → Web3jEpochCommitPublisher            (production)
 */
@Configuration
public class CommitConfig {

    /**
     * Logging stub publisher — active when on-chain commit is disabled (default).
     * Pins the epoch blob to IPFS and logs the commit details without submitting
     * an on-chain transaction.
     */
    @Bean
    @ConditionalOnProperty(
            name = "relay.chain.on-chain-commit-enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    public EpochCommitPublisher loggingEpochCommitPublisher(
            EpochBlobAssembler blobAssembler,
            IpfsPinClient ipfsPinClient
    ) {
        return new LoggingEpochCommitPublisher(blobAssembler, ipfsPinClient);
    }

    // Web3jEpochCommitPublisher bean will be added here once implemented:
    //
    // @Bean
    // @ConditionalOnProperty(name = "relay.chain.on-chain-commit-enabled", havingValue = "true")
    // public EpochCommitPublisher web3jEpochCommitPublisher(...) { ... }
}
