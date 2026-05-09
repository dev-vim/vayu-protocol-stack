package protocol.vayu.relay.service.commit.aggregation;

import org.junit.jupiter.api.Test;
import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultEpochAggregatorTest {

    private final DefaultEpochAggregator aggregator = new DefaultEpochAggregator();

    @Test
    void aggregateShouldGroupByH3AndComputeAverages() {
        long epochId = 100;
        List<ReadingSubmissionRequest> readings = List.of(
                reading("0x1111111111111111111111111111111111111111", "0x0882830a1fffffff", epochId, 100, 200, null),
                reading("0x2222222222222222222222222222222222222222", "0x0882830a1fffffff", epochId, 200, 300, 10),
                reading("0x1111111111111111111111111111111111111111", "0x0882830a1ffffffe", epochId, 150, 250, 20)
        );

        EpochAggregate aggregate = aggregator.aggregate(epochId, readings);

        assertEquals(epochId, aggregate.epochId());
        assertEquals(3, aggregate.totalReadings());
        assertEquals(2, aggregate.uniqueReporters());
        assertEquals(2, aggregate.cells().size());

        CellAggregate firstCell = aggregate.cells().stream()
                .filter(cell -> cell.h3Index().equals("0x0882830a1fffffff"))
                .findFirst()
                .orElseThrow();

        assertEquals(2, firstCell.readingCount());
        assertEquals(150, firstCell.medianAqi());
        assertEquals(250, firstCell.avgPm25());
        assertEquals(5, firstCell.avgPm10());
    }

    private static ReadingSubmissionRequest reading(
            String reporter,
            String h3Index,
            long epochId,
            int aqi,
            int pm25,
            Integer pm10
    ) {
        return new ReadingSubmissionRequest(
                reporter,
                h3Index,
                epochId,
                (epochId * 3600) + 1,
                aqi,
                pm25,
                pm10,
                null,
                null,
                null,
                null,
                "0x" + "1".repeat(130)
        );
    }
}
