package protocol.vayu.relay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "relay")
public record RelayProperties(Epoch epoch, Validation validation) {

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
            String h3Format,
            String h3Hex,
            String h3Resolution,
            String rateLimit
        ) {
    }
}
