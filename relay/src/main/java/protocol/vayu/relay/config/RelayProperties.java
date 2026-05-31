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

    public record Security(boolean signatureVerificationEnabled, boolean stakeCheckEnabled, Eip712 eip712, StakeCache stakeCache) {
    }

    /**
     * Caching and circuit-breaker configuration for the on-chain stake weight provider.
     * Only relevant when {@code relay.security.stake-check-enabled=true}.
     */
    public record StakeCache(
            /** How long a fetched stake value remains fresh in the hot cache (seconds). */
            long ttlSeconds,
            /** Maximum number of reporter entries in the hot cache. */
            long maxSize,
            /**
             * When true (default), an RPC failure falls back to a stale/cached stake value,
             * or {@code BigInteger.ONE} when no prior value exists. The on-chain contract is
             * the authoritative settlement layer, so accepting a potentially-low-stake reporter
             * briefly is preferable to rejecting all submissions during an RPC outage.
             * When false, the circuit breaker failing causes a 503 Service Unavailable response.
             */
            boolean failOpen,
            /** Resilience4j: percentage of calls that must fail before the circuit opens (0-100). */
            int cbFailureRateThreshold,
            /** Resilience4j: seconds the circuit stays open before entering half-open state. */
            long cbWaitDurationSeconds,
            /** Resilience4j: sliding window size (number of calls) for failure-rate calculation. */
            int cbSlidingWindowSize
    ) {
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
            long chainId,
            /** TCP connect timeout for the JSON-RPC HTTP client (milliseconds). Default: 5000. */
            int rpcConnectTimeoutMs,
            /** Socket read timeout for the JSON-RPC HTTP client (milliseconds). Default: 10000. */
            int rpcReadTimeoutMs
    ) {
    }
}
