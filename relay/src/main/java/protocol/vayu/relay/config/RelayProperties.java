package protocol.vayu.relay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "relay")
public record RelayProperties(Epoch epoch, Validation validation, Security security) {

    public record Epoch(long durationSeconds, long commitCheckIntervalMs, long timestampToleranceSeconds) {
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
