package protocol.vayu.relay.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Central home for all relay Micrometer metrics.
 *
 * <p>Metrics exposed:
 * <ul>
 *   <li>{@code vayu.readings.accepted} — counter, incremented on every successfully ingested reading.</li>
 *   <li>{@code vayu.readings.rejected} — counter with {@code reason} tag:
 *       {@code invalid_signature}, {@code duplicate}, {@code rate_limited},
 *       {@code no_stake}, {@code validation_error}, {@code stale_timestamp},
 *       {@code wrong_epoch}, {@code wrong_resolution}.</li>
 *   <li>{@code vayu.epoch.commits} — counter with {@code outcome} tag:
 *       {@code success}, {@code empty}, {@code failure}.</li>
 *   <li>{@code vayu.epoch.readings_drained} — distribution summary recording how many
 *       readings were drained per committed epoch.</li>
 * </ul>
 *
 * <p>The {@code vayu.ingress.pending} gauge is registered directly by
 * {@link protocol.vayu.relay.service.commit.InMemoryEpochIngressWindow} via
 * {@link io.micrometer.core.instrument.Gauge}.
 */
@Component
public class RelayMetrics {

    private static final String READINGS_ACCEPTED    = "vayu.readings.accepted";
    private static final String READINGS_REJECTED    = "vayu.readings.rejected";
    private static final String EPOCH_COMMITS        = "vayu.epoch.commits";
    private static final String READINGS_DRAINED     = "vayu.epoch.readings_drained";

    private final Counter readingsAccepted;

    private final Counter rejectedInvalidSignature;
    private final Counter rejectedDuplicate;
    private final Counter rejectedRateLimited;
    private final Counter rejectedNoStake;
    private final Counter rejectedValidationError;
    private final Counter rejectedStaleTimestamp;
    private final Counter rejectedWrongEpoch;
    private final Counter rejectedWrongResolution;

    private final Counter commitSuccess;
    private final Counter commitEmpty;
    private final Counter commitFailure;

    private final DistributionSummary readingsDrained;

    public RelayMetrics(MeterRegistry registry) {
        readingsAccepted = Counter.builder(READINGS_ACCEPTED)
                .description("Readings successfully accepted into the ingress window")
                .register(registry);

        rejectedInvalidSignature = rejectedCounter(registry, "invalid_signature");
        rejectedDuplicate        = rejectedCounter(registry, "duplicate");
        rejectedRateLimited      = rejectedCounter(registry, "rate_limited");
        rejectedNoStake          = rejectedCounter(registry, "no_stake");
        rejectedValidationError  = rejectedCounter(registry, "validation_error");
        rejectedStaleTimestamp   = rejectedCounter(registry, "stale_timestamp");
        rejectedWrongEpoch       = rejectedCounter(registry, "wrong_epoch");
        rejectedWrongResolution  = rejectedCounter(registry, "wrong_resolution");

        commitSuccess = commitCounter(registry, "success");
        commitEmpty   = commitCounter(registry, "empty");
        commitFailure = commitCounter(registry, "failure");

        readingsDrained = DistributionSummary.builder(READINGS_DRAINED)
                .description("Number of readings drained per committed epoch")
                .register(registry);
    }

    // ── Ingestion ─────────────────────────────────────────────────────────────

    public void recordAccepted()             { readingsAccepted.increment(); }
    public void recordRejectedInvalidSig()   { rejectedInvalidSignature.increment(); }
    public void recordRejectedDuplicate()    { rejectedDuplicate.increment(); }
    public void recordRejectedRateLimited()  { rejectedRateLimited.increment(); }
    public void recordRejectedNoStake()      { rejectedNoStake.increment(); }
    public void recordRejectedValidation()   { rejectedValidationError.increment(); }
    public void recordRejectedStaleTs()      { rejectedStaleTimestamp.increment(); }
    public void recordRejectedWrongEpoch()   { rejectedWrongEpoch.increment(); }
    public void recordRejectedWrongRes()     { rejectedWrongResolution.increment(); }

    // ── Epoch commits ─────────────────────────────────────────────────────────

    public void recordCommitSuccess()                  { commitSuccess.increment(); }
    public void recordCommitEmpty()                    { commitEmpty.increment(); }
    public void recordCommitFailure()                  { commitFailure.increment(); }
    public void recordReadingsDrained(int count)       { readingsDrained.record(count); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Counter rejectedCounter(MeterRegistry registry, String reason) {
        return Counter.builder(READINGS_REJECTED)
                .description("Readings rejected during ingestion, tagged by reason")
                .tag("reason", reason)
                .register(registry);
    }

    private static Counter commitCounter(MeterRegistry registry, String outcome) {
        return Counter.builder(EPOCH_COMMITS)
                .description("Epoch commit cycle outcomes")
                .tag("outcome", outcome)
                .register(registry);
    }
}
