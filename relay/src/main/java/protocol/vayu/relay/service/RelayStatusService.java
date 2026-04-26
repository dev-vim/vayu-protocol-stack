package protocol.vayu.relay.service;

import protocol.vayu.relay.api.dto.HealthStatusResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class RelayStatusService {

    private final long startEpochSecond = Instant.now().getEpochSecond();

    @Value("${spring.application.name:vayu-relay}")
    private String appName;

    public HealthStatusResponse getStatus() {
        long now = Instant.now().getEpochSecond();
        return new HealthStatusResponse(
                "healthy",
                appName,
                now / 3600,
                84532, // Sepolia testnet
                0,
                true,
                0,
                "",
                now - startEpochSecond
        );
    }
}
