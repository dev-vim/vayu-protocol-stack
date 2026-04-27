package protocol.vayu.relay.service.commit;

/**
 * Score assigned to a reporter for a single epoch cell.
 * score = max(0, 1 − |reporterAqi − cellMedianAqi| / scoringTolerance)
 */
public record ReporterScore(String reporter, double score) {

    public boolean isZero() {
        return score == 0.0;
    }
}
