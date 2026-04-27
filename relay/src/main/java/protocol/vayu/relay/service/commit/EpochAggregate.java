package protocol.vayu.relay.service.commit;

import java.util.List;

public record EpochAggregate(
        long epochId,
        int totalReadings,
        int uniqueReporters,
        List<CellAggregate> cells
) {
}
