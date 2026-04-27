package protocol.vayu.relay.service.commit;

public record CellAggregate(
        String h3Index,
        int readingCount,
        int avgAqi,
        int avgPm25,
        int avgPm10,
        int avgO3,
        int avgNo2,
        int avgSo2,
        int avgCo
) {
}
