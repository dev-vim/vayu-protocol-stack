package protocol.vayu.relay.service.commit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.web3j.utils.Numeric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the epoch blob — a canonical JSON document describing the full
 * output of an epoch commit cycle — that is uploaded to IPFS before the
 * on-chain {@code commitEpoch()} call.
 *
 * The CID returned by IPFS is passed as the {@code ipfsCid} argument to
 * {@code commitEpoch()}, giving anyone the ability to audit the full epoch data
 * from only the on-chain transaction.
 *
 * Field ordering is fixed (LinkedHashMap) and BigInteger amounts are serialised
 * as decimal strings to avoid floating-point precision loss in downstream parsers.
 */
@Component
public class EpochBlobAssembler {

    private final ObjectMapper objectMapper;

    public EpochBlobAssembler() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Serialises the given aggregate to a compact JSON string.
     *
     * @throws IllegalStateException if serialisation fails (should never happen in practice).
     */
    public String assemble(EpochAggregate aggregate) {
        Map<String, Object> blob = new LinkedHashMap<>();
        blob.put("epochId", aggregate.epochId());
        blob.put("totalReadings", aggregate.totalReadings());
        blob.put("uniqueReporters", aggregate.uniqueReporters());
        blob.put("activeCells", aggregate.activeCells());
        blob.put("dataRoot", hexOrNull(aggregate.dataRoot()));
        blob.put("rewardRoot", hexOrNull(aggregate.rewardRoot()));
        blob.put("cells", aggregate.cells().stream().map(this::cellToMap).toList());
        blob.put("rewards", aggregate.rewards().stream().map(this::rewardToMap).toList());
        blob.put("penaltyList", aggregate.penaltyList());

        try {
            return objectMapper.writeValueAsString(blob);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialise epoch blob for epochId=" + aggregate.epochId(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> cellToMap(CellAggregate cell) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("h3Index", cell.h3Index());
        map.put("readingCount", cell.readingCount());
        map.put("active", cell.active());
        map.put("medianAqi", cell.medianAqi());
        map.put("avgPm25", cell.avgPm25());
        map.put("avgPm10", cell.avgPm10());
        map.put("avgO3", cell.avgO3());
        map.put("avgNo2", cell.avgNo2());
        map.put("avgSo2", cell.avgSo2());
        map.put("avgCo", cell.avgCo());
        map.put("reporterScores", cell.reporterScores().stream()
                .map(rs -> Map.of("reporter", rs.reporter(), "score", rs.score()))
                .toList());
        return map;
    }

    private Map<String, Object> rewardToMap(ReporterReward reward) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reporter", reward.reporter());
        map.put("h3IndexLong", reward.h3IndexLong());
        // Serialise as decimal string — avoids IEEE 754 precision loss for large wei values
        map.put("amount", reward.amount().toString());
        return map;
    }

    private static String hexOrNull(byte[] bytes) {
        return bytes != null ? Numeric.toHexString(bytes) : null;
    }

    // Package-private for testing
    List<String> penaltyList(EpochAggregate aggregate) {
        return aggregate.penaltyList();
    }
}
