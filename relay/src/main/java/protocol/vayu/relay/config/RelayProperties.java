package protocol.vayu.relay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "relay")
public record RelayProperties(Epoch epoch, Validation validation, Security security, Ipfs ipfs, Chain chain) {

    public record Epoch(
            long durationSeconds,
            long commitCheckIntervalMs,
            long timestampToleranceSeconds,
            /** Minimum distinct reporters per cell for it to be counted as active. Protocol: 3. */
            int minReportersPerCell,
            /** AQI deviation tolerance used in reporter scoring. Protocol: 50 (SPATIAL_TOLERANCE_AQI). */
            int scoringToleranceAqi,
            /**
             * Epoch reward budget in token wei, matching VayuRewards.EPOCH_BUDGET.
             * Protocol value: (60_000_000 * 1e18) / 87_600 = 684931506849315068493 wei.
             */
            java.math.BigInteger epochBudgetWei
    ) {
    }

    public record Validation(
            int requiredH3Resolution,
            long rateLimitWindowSeconds,
            int minAqi,
            int minPm25,
            Messages messages
    ) {
    }

    public record Messages(
            String aqiMin,
            String pm25Min,
            String timestampRequired,
            String timestampTolerance,
            String epochIdMismatch,
            String h3Format,
            String h3Hex,
            String h3Resolution,
            String rateLimit
    ) {
    }

    public record Security(boolean signatureVerificationEnabled, boolean stakeCheckEnabled, Eip712 eip712) {
    }

    public record Eip712(String domainName, String domainVersion, long chainId, String verifyingContract) {
    }

    /**
     * IPFS pinning configuration.
     * {@code provider} selects the active implementation: {@code kubo} (local node, default)
     * or {@code pinata} (managed service).
     */
    public record Ipfs(
            /** Active IPFS provider: "kubo" or "pinata". Defaults to "kubo". */
            String provider,
            /** Base URL of the Kubo RPC API, e.g. http://localhost:5001 */
            String kuboApiUrl,
            /** Pinata API JWT (Bearer token). Set via RELAY_IPFS_PINATA_JWT env var in prod. */
            String pinataJwt,
            /** Pinata API endpoint. Set via RELAY_IPFS_PINATA_ENDPOINT env var in prod. */
            String pinataEndpoint
    ) {
    }

    /**
     * EVM chain configuration for contract reads (stake checks) and writes (epoch commits).
     */
    public record Chain(
            /** JSON-RPC endpoint, e.g. https://sepolia.base.org */
            String rpcUrl,
            /** Deployed VayuEpochSettlement contract address (0x-prefixed). */
            String settlementAddress,
            /** When true, use Web3jEpochCommitPublisher to submit real on-chain transactions. */
            boolean onChainCommitEnabled,
            /** Relay wallet private key (hex, no 0x prefix). Set via RELAY_CHAIN_RELAY_PRIVATE_KEY env var. */
            String relayPrivateKey,
            /** EIP-155 chain ID used for transaction signing (e.g. 84532 for Base Sepolia). */
            long chainId
    ) {
    }
}
