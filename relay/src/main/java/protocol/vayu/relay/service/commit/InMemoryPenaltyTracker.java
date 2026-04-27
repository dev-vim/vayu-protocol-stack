package protocol.vayu.relay.service.commit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory PenaltyTracker. State is lost on restart; replace with a persistent
 * store (e.g. database-backed) for production use.
 *
 * Mirrors VayuTypes.CONSECUTIVE_ZERO_SCORES_THRESHOLD = 10.
 */
@Component
public class InMemoryPenaltyTracker implements PenaltyTracker {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryPenaltyTracker.class);

    /** Matches VayuTypes.CONSECUTIVE_ZERO_SCORES_THRESHOLD. */
    static final int THRESHOLD = 10;

    private final ConcurrentHashMap<String, Integer> consecutiveZeros = new ConcurrentHashMap<>();

    @Override
    public void recordScores(long epochId, Map<String, Double> reporterScores) {
        for (Map.Entry<String, Double> entry : reporterScores.entrySet()) {
            String reporter = entry.getKey();
            if (entry.getValue() == 0.0) {
                consecutiveZeros.merge(reporter, 1, Integer::sum);
            } else {
                consecutiveZeros.put(reporter, 0);
            }
        }
    }

    @Override
    public List<String> penaltyList(long epochId) {
        List<String> penalty = new ArrayList<>();
        consecutiveZeros.forEach((reporter, count) -> {
            if (count >= THRESHOLD) {
                penalty.add(reporter);
            }
        });
        // Reset counter for penalised reporters so they are not double-slashed
        penalty.forEach(r -> consecutiveZeros.put(r, 0));
        if (!penalty.isEmpty()) {
            LOG.info("epoch {} penalty list: {} reporter(s) flagged for auto-slash", epochId, penalty.size());
        }
        return penalty;
    }
}
