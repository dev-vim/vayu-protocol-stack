package protocol.vayu.relay.service.commit;

import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Basic scaffold aggregator. Computes per-cell arithmetic mean (not median),
 * does not score reporters, does not build Merkle trees, and does not derive
 * the penalty list. Suitable for development/testing only.
 *
 * @see ProtocolEpochAggregator for the protocol-compliant implementation.
 */
@Component
public class DefaultEpochAggregator implements EpochAggregator {

    @Override
    public EpochAggregate aggregate(long epochId, List<ReadingSubmissionRequest> readings) {
        if (readings == null || readings.isEmpty()) {
            return new EpochAggregate(epochId, 0, 0, List.of(), 0, List.of(), null, null, List.of());
        }

        Map<String, Stats> perCell = new LinkedHashMap<>();
        Set<String> uniqueReporters = new LinkedHashSet<>();

        for (ReadingSubmissionRequest reading : readings) {
            uniqueReporters.add(reading.reporter());
            Stats stats = perCell.computeIfAbsent(reading.h3Index(), ignored -> new Stats());
            stats.add(reading);
        }

        List<CellAggregate> cells = new ArrayList<>();
        for (Map.Entry<String, Stats> entry : perCell.entrySet()) {
            cells.add(entry.getValue().toAggregate(entry.getKey()));
        }

        return new EpochAggregate(epochId, readings.size(), uniqueReporters.size(), cells,
                0, List.of(), null, null, List.of());
    }

    private static final class Stats {
        private int readingCount;
        private long sumAqi;
        private long sumPm25;
        private long sumPm10;
        private long sumO3;
        private long sumNo2;
        private long sumSo2;
        private long sumCo;

        private void add(ReadingSubmissionRequest reading) {
            readingCount++;
            sumAqi  += reading.aqi();
            sumPm25 += reading.pm25();
            sumPm10 += orZero(reading.pm10());
            sumO3   += orZero(reading.o3());
            sumNo2  += orZero(reading.no2());
            sumSo2  += orZero(reading.so2());
            sumCo   += orZero(reading.co());
        }

        private CellAggregate toAggregate(String h3Index) {
            return new CellAggregate(
                    h3Index,
                    readingCount,
                    false,          // active not computed by this aggregator
                    avg(sumAqi),    // medianAqi field — approximated as mean here
                    avg(sumPm25),
                    avg(sumPm10),
                    avg(sumO3),
                    avg(sumNo2),
                    avg(sumSo2),
                    avg(sumCo),
                    List.of()       // reporterScores not computed
            );
        }

        private int avg(long sum) {
            return (int) Math.round((double) sum / readingCount);
        }

        private int orZero(Integer value) {
            return value == null ? 0 : value;
        }
    }
}
