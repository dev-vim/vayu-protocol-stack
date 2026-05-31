package protocol.vayu.relay.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import protocol.vayu.relay.service.commit.InMemoryEpochIngressWindow;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RelayMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RelayMetrics metrics = new RelayMetrics(registry);

    // ── Accepted counter ──────────────────────────────────────────────────────

    @Test
    void recordAcceptedIncrementsAcceptedCounter() {
        metrics.recordAccepted();
        assertEquals(1.0, registry.counter("vayu.readings.accepted").count());
    }

    // ── Rejection counters ────────────────────────────────────────────────────

    @Test
    void eachRejectionReasonIncrementsCorrectTaggedCounter() {
        metrics.recordRejectedInvalidSig();
        metrics.recordRejectedDuplicate();
        metrics.recordRejectedRateLimited();
        metrics.recordRejectedNoStake();
        metrics.recordRejectedStakeUnavailable();
        metrics.recordRejectedValidation();
        metrics.recordRejectedStaleTs();
        metrics.recordRejectedWrongEpoch();
        metrics.recordRejectedWrongRes();

        for (String reason : List.of(
                "invalid_signature", "duplicate", "rate_limited", "no_stake", "stake_unavailable",
                "validation_error", "stale_timestamp", "wrong_epoch", "wrong_resolution")) {
            assertEquals(1.0,
                    registry.counter("vayu.readings.rejected", "reason", reason).count(),
                    "expected count=1 for reason=" + reason);
        }
    }

    @Test
    void rejectionCountersAreScopedByReasonTag() {
        metrics.recordRejectedInvalidSig();
        metrics.recordRejectedInvalidSig();
        metrics.recordRejectedDuplicate();

        assertEquals(2.0, registry.counter("vayu.readings.rejected", "reason", "invalid_signature").count());
        assertEquals(1.0, registry.counter("vayu.readings.rejected", "reason", "duplicate").count());
        assertEquals(0.0, registry.counter("vayu.readings.rejected", "reason", "rate_limited").count());
    }

    // ── Commit counters ───────────────────────────────────────────────────────

    @Test
    void eachCommitOutcomeIncrementsCorrectTaggedCounter() {
        metrics.recordCommitSuccess();
        metrics.recordCommitEmpty();
        metrics.recordCommitFailure();

        assertEquals(1.0, registry.counter("vayu.epoch.commits", "outcome", "success").count());
        assertEquals(1.0, registry.counter("vayu.epoch.commits", "outcome", "empty").count());
        assertEquals(1.0, registry.counter("vayu.epoch.commits", "outcome", "failure").count());
    }

    @Test
    void commitCountersAreScopedByOutcomeTag() {
        metrics.recordCommitSuccess();
        metrics.recordCommitSuccess();
        metrics.recordCommitEmpty();

        assertEquals(2.0, registry.counter("vayu.epoch.commits", "outcome", "success").count());
        assertEquals(1.0, registry.counter("vayu.epoch.commits", "outcome", "empty").count());
        assertEquals(0.0, registry.counter("vayu.epoch.commits", "outcome", "failure").count());
    }

    // ── Distribution summary ──────────────────────────────────────────────────

    @Test
    void recordReadingsDrainedUpdatesDistributionSummary() {
        metrics.recordReadingsDrained(7);
        metrics.recordReadingsDrained(3);

        assertEquals(2, registry.summary("vayu.epoch.readings_drained").count());
        assertEquals(10.0, registry.summary("vayu.epoch.readings_drained").totalAmount());
    }

    @Test
    void recordReadingsDrainedWithZeroReadingsIsRecorded() {
        metrics.recordReadingsDrained(0);

        assertEquals(1, registry.summary("vayu.epoch.readings_drained").count());
        assertEquals(0.0, registry.summary("vayu.epoch.readings_drained").totalAmount());
    }

    // ── Ingress pending gauge ─────────────────────────────────────────────────

    @Test
    void ingressPendingGaugeIsRegisteredByInMemoryWindow() {
        new InMemoryEpochIngressWindow(registry);
        assertNotNull(registry.find("vayu.ingress.pending").gauge());
    }

    @Test
    void ingressPendingGaugeTracksLivePendingCount() {
        InMemoryEpochIngressWindow window = new InMemoryEpochIngressWindow(registry);

        assertEquals(0.0, registry.find("vayu.ingress.pending").gauge().value());

        window.enqueue(reading(1L));
        window.enqueue(reading(1L));
        assertEquals(2.0, registry.find("vayu.ingress.pending").gauge().value());

        window.drainEpoch(1L);
        assertEquals(0.0, registry.find("vayu.ingress.pending").gauge().value());
    }

    @Test
    void ingressPendingGaugeAccumulatesAcrossMultipleEpochs() {
        InMemoryEpochIngressWindow window = new InMemoryEpochIngressWindow(registry);

        window.enqueue(reading(1L));
        window.enqueue(reading(2L));
        window.enqueue(reading(2L));
        assertEquals(3.0, registry.find("vayu.ingress.pending").gauge().value());

        window.drainEpoch(1L);
        assertEquals(2.0, registry.find("vayu.ingress.pending").gauge().value());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static ReadingSubmissionRequest reading(long epochId) {
        return new ReadingSubmissionRequest(
                "0x1111111111111111111111111111111111111111",
                "0x0882830a1fffffff",
                epochId, epochId * 3600L + 1,
                120, 350, null, null, null, null, null,
                "0x" + "1".repeat(130)
        );
    }
}
