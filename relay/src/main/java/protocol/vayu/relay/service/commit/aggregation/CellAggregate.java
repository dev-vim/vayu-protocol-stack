package protocol.vayu.relay.service.commit.aggregation;

import java.util.List;

public record CellAggregate(
        String h3Index,
        int readingCount,
        /** true when distinct reporter count >= MIN_REPORTERS_PER_CELL (3). */
        boolean active,
        /** Median AQI across all readings in this cell for this epoch. */
        int medianAqi,
        int avgPm25,
        int avgPm10,
        int avgO3,
        int avgNo2,
        int avgSo2,
        int avgCo,
        /** Per-reporter scores; empty when computed by the basic DefaultEpochAggregator. */
        List<ReporterScore> reporterScores
) {
}
