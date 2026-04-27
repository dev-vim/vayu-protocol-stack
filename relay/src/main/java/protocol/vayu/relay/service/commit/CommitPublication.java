package protocol.vayu.relay.service.commit;

public record CommitPublication(
        long epochId,
        String txHash,
        int readingCount,
        long submittedAt
) {
}
