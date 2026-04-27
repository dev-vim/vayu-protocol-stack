package protocol.vayu.relay.service.commit;

import java.util.List;
import java.util.Map;

/**
 * Tracks consecutive zero-score epochs per reporter to derive the penalty list
 * that goes into commitEpoch().
 *
 * Contract: reportScores() must be called once per completed epoch, in epoch order.
 * A reporter on the returned penalty list has its consecutive counter reset to 0.
 */
public interface PenaltyTracker {

    /**
     * Record per-reporter scores for the given epoch.
     * A score of 0 increments the reporter's consecutive-zero counter;
     * any positive score resets it.
     *
     * @param epochId epoch just aggregated
     * @param reporterScores map of reporter address → score (0 = zero score)
     */
    void recordScores(long epochId, Map<String, Double> reporterScores);

    /**
     * Returns the list of reporter addresses that have accumulated
     * {@code CONSECUTIVE_ZERO_SCORES_THRESHOLD} (10) or more consecutive
     * zero-score epochs and should be auto-slashed in this commit.
     * Resets the counter to 0 for each returned reporter.
     *
     * @param epochId epoch being committed (informational, for logging)
     * @return addresses eligible for auto-slash; empty if none
     */
    List<String> penaltyList(long epochId);
}
