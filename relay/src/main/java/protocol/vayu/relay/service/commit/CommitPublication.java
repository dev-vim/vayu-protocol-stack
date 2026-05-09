package protocol.vayu.relay.service.commit;

public record CommitPublication(
        long epochId,
        String txHash,
        String ipfsCid,
        int readingCount,
        long submittedAt
) {
}
