package protocol.vayu.relay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "relay")
public record RelayProperties(Epoch epoch) {

    public record Epoch(long durationSeconds, long commitCheckIntervalMs, long timestampToleranceSeconds) {
    }
}
