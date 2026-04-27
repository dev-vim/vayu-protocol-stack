package protocol.vayu.relay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "relay")
public record RelayProperties(Epoch epoch, Validation validation, Security security) {

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
}
