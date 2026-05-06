package protocol.vayu.relay.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import protocol.vayu.relay.service.commit.EpochCommitPublisher;
import protocol.vayu.relay.service.commit.LoggingEpochCommitPublisher;
import protocol.vayu.relay.service.commit.Web3jEpochCommitPublisher;
import protocol.vayu.relay.service.commit.ipfs.EpochBlobAssembler;
import protocol.vayu.relay.service.commit.ipfs.IpfsPinClient;

/**
 * Selects the active {@link EpochCommitPublisher} implementation based on
 * {@code relay.chain.on-chain-commit-enabled}.
 *
 *   on-chain-commit-enabled=false  → {@link LoggingEpochCommitPublisher}  (default; dev/CI)
 *   on-chain-commit-enabled=true   → {@link Web3jEpochCommitPublisher}    (production)
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

    /**
     * Production publisher — active when on-chain commit is enabled.
     * Submits a signed {@code commitEpoch} transaction to the settlement contract.
     * Requires {@code relay.chain.relay-private-key} and {@code relay.chain.chain-id}.
     */
    @Bean
    @ConditionalOnProperty(name = "relay.chain.on-chain-commit-enabled", havingValue = "true")
    public EpochCommitPublisher web3jEpochCommitPublisher(
            RelayProperties props,
            EpochBlobAssembler blobAssembler,
            IpfsPinClient ipfsPinClient
    ) {
        Web3j web3j = Web3j.build(new HttpService(props.chain().rpcUrl()));
        Credentials credentials = Credentials.create(props.chain().relayPrivateKey());
        return new Web3jEpochCommitPublisher(
                web3j,
                credentials,
                props.chain().settlementAddress(),
                props.chain().chainId(),
                blobAssembler,
                ipfsPinClient
        );
    }
}
