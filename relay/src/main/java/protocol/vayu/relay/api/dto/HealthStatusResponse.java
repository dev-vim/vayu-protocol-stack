package protocol.vayu.relay.api.dto;

public record HealthStatusResponse(
        String status,
        String version,
        long currentEpochId,
        long chainId,
        long blockNumber,
        boolean commitWorkerRunning,
        long lastCommittedEpoch,
        String lastCommitTxHash,
        long uptimeSeconds
) {
}
