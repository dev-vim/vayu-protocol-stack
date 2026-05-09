package protocol.vayu.relay.service;

import protocol.vayu.relay.api.dto.HealthStatusResponse;
import protocol.vayu.relay.config.RelayProperties;
import protocol.vayu.relay.service.commit.CommitCycleState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class RelayStatusService {

    private final long startEpochSecond = Instant.now().getEpochSecond();
    private final RelayProperties relayProperties;
    private final CommitCycleState commitCycleState;

    public RelayStatusService(RelayProperties relayProperties, CommitCycleState commitCycleState) {
        this.relayProperties = relayProperties;
        this.commitCycleState = commitCycleState;
    }

    @Value("${spring.application.name:vayu-relay}")
    private String appName;

    public HealthStatusResponse getStatus() {
        long now = Instant.now().getEpochSecond();
        long epochDuration = Math.max(1, relayProperties.epoch().durationSeconds());
        long currentEpochId = now / epochDuration;
        long chainId = relayProperties.security().eip712().chainId();
        long heartbeatTimeoutSeconds = Math.max(
                1,
                (relayProperties.epoch().commitCheckIntervalMs() * 3) / 1000
        );
        boolean commitWorkerRunning = commitCycleState.isWorkerRunning(now, heartbeatTimeoutSeconds);
        String status = commitWorkerRunning ? "healthy" : "degraded";

        return new HealthStatusResponse(
                status,
                appName,
                currentEpochId,
                chainId,
                0,
                commitWorkerRunning,
                Math.max(0, commitCycleState.lastCommittedEpoch()),
                commitCycleState.lastCommitTxHash(),
                now - startEpochSecond
        );
    }
}
