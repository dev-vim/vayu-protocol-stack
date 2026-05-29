package protocol.vayu.relay.service.ingestion;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import protocol.vayu.relay.api.dto.ReadingAcceptedResponse;
import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import protocol.vayu.relay.api.error.RelayApiException;
import protocol.vayu.relay.config.RelayProperties;
import protocol.vayu.relay.service.RelayMetrics;
import protocol.vayu.relay.service.commit.InMemoryEpochIngressWindow;
import org.springframework.http.HttpStatus;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadingIngestionServiceTest {

    private final ReadingIngestionService service = new ReadingIngestionService(
            relayProperties(false, false),
            request -> true,
            reporter -> true
    );

    @Test
    void ingestShouldAcceptValidReading() {
        ReadingSubmissionRequest request = validRequest(
                "0x1111111111111111111111111111111111111111",
                Instant.now().getEpochSecond()
        );

        ReadingAcceptedResponse response = service.ingest(request);

        assertEquals("accepted", response.status());
        assertTrue(response.receivedAt() > 0);
        assertEquals(response.receivedAt() / 3600, response.epochId());
    }

    @Test
    void ingestShouldRejectAqiBelowConfiguredMinimum() {
        ReadingSubmissionRequest request = new ReadingSubmissionRequest(
                "0x2222222222222222222222222222222222222222",
                "0x0882830a1fffffff",
            Instant.now().getEpochSecond() / 3600,
            Instant.now().getEpochSecond(),
                0,
                300,
                null,
                null,
                null,
                null,
                null,
                signature()
        );

        RelayApiException ex = assertThrows(RelayApiException.class, () -> service.ingest(request));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("invalid_request", ex.errorCode());
        assertEquals("aqi must be greater than 1", ex.getMessage());
    }

    @Test
    void ingestShouldRejectTimestampOutsideTolerance() {
        long staleTimestamp = Instant.now().minusSeconds(1000).getEpochSecond();
        ReadingSubmissionRequest request = validRequest(
                "0x3333333333333333333333333333333333333333",
                staleTimestamp
        );

        RelayApiException ex = assertThrows(RelayApiException.class, () -> service.ingest(request));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("invalid_request", ex.errorCode());
        assertEquals("timestamp is outside allowed tolerance window", ex.getMessage());
    }

    @Test
    void ingestShouldRejectWrongH3Resolution() {
        ReadingSubmissionRequest request = new ReadingSubmissionRequest(
                "0x4444444444444444444444444444444444444444",
                "0x0872830a1fffffff",
            Instant.now().getEpochSecond() / 3600,
            Instant.now().getEpochSecond(),
                120,
                350,
                null,
                null,
                null,
                null,
                null,
                signature()
        );

        RelayApiException ex = assertThrows(RelayApiException.class, () -> service.ingest(request));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("invalid_request", ex.errorCode());
        assertEquals("h3Index resolution must be 8", ex.getMessage());
    }

    @Test
    void ingestShouldRateLimitReporterWithinConfiguredWindow() {
        long now = Instant.now().getEpochSecond();
        ReadingSubmissionRequest request = validRequest(
                "0x5555555555555555555555555555555555555555",
                now
        );

        ReadingAcceptedResponse first = service.ingest(request);
        assertNotNull(first);

        RelayApiException ex = assertThrows(RelayApiException.class, () -> service.ingest(request));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.status());
        assertEquals("rate_limited", ex.errorCode());
        assertEquals("reporter can submit once every 300 seconds", ex.getMessage());
        assertNotNull(ex.retryAfter());
        assertTrue(ex.retryAfter() > 0);
        assertTrue(ex.retryAfter() <= 300);
    }

        @Test
        void ingestShouldRejectInvalidSignatureWhenVerificationEnabled() {
        ReadingIngestionService strictService = new ReadingIngestionService(
            relayProperties(true, false),
            request -> false,
            reporter -> true
        );

        ReadingSubmissionRequest request = validRequest(
            "0x6666666666666666666666666666666666666666",
            Instant.now().getEpochSecond()
        );

        RelayApiException ex = assertThrows(RelayApiException.class, () -> strictService.ingest(request));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("invalid_request", ex.errorCode());
        assertEquals("invalid signature", ex.getMessage());
        }

        @Test
        void ingestShouldRejectReporterWithNoStakeWhenStakeCheckEnabled() {
        ReadingIngestionService strictService = new ReadingIngestionService(
            relayProperties(false, true),
            request -> true,
            reporter -> false
        );

        ReadingSubmissionRequest request = validRequest(
            "0x7777777777777777777777777777777777777777",
            Instant.now().getEpochSecond()
        );

        RelayApiException ex = assertThrows(RelayApiException.class, () -> strictService.ingest(request));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.status());
        assertEquals("unauthorized", ex.errorCode());
        assertEquals("reporter has no active stake", ex.getMessage());
    }

    @Test
    void ingestShouldRejectEpochIdMismatch() {
        long now = Instant.now().getEpochSecond();
        ReadingSubmissionRequest request = new ReadingSubmissionRequest(
                "0x8888888888888888888888888888888888888888",
                "0x0882830a1fffffff",
                (now / 3600) + 1,
            now,
                120,
                350,
                null,
                null,
                null,
                null,
                null,
                signature()
        );

        RelayApiException ex = assertThrows(RelayApiException.class, () -> service.ingest(request));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("invalid_request", ex.errorCode());
        assertEquals("epochId does not match timestamp and epoch duration", ex.getMessage());
    }

    @Test
    void ingestShouldQueueAcceptedReadingForCommitCycle() {
        InMemoryEpochIngressWindow store = new InMemoryEpochIngressWindow(new SimpleMeterRegistry());
        ReadingIngestionService queueingService = new ReadingIngestionService(
                relayProperties(false, false),
                request -> true,
                reporter -> true,
                store
        );

        ReadingSubmissionRequest request = validRequest(
                "0x9999999999999999999999999999999999999999",
                Instant.now().getEpochSecond()
        );

        ReadingAcceptedResponse response = queueingService.ingest(request);

        assertEquals("accepted", response.status());
        assertEquals(1, store.pendingReadings());
        assertEquals(1, store.drainEpoch(request.epochId()).size());
        assertEquals(0, store.pendingReadings());
    }

    @Test
    void ingestShouldRejectDuplicateReadingForSameEpochAndCell() {
        InMemoryEpochIngressWindow store = new InMemoryEpochIngressWindow(new SimpleMeterRegistry());
        ReadingIngestionService svc = new ReadingIngestionService(
                relayProperties(false, false),
                request -> true,
                reporter -> true,
                store
        );

        long now = Instant.now().getEpochSecond();
        // Two requests with the same reporter + epochId + h3Index but submitted a second apart
        // to avoid the rate-limit window firing first (different reporters used here to isolate
        // the replay guard, but same cell is the key factor).
        ReadingSubmissionRequest first = validRequest(
                "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", now);
        ReadingSubmissionRequest duplicate = validRequest(
                "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", now);

        ReadingAcceptedResponse accepted = svc.ingest(first);
        assertEquals("accepted", accepted.status());

        RelayApiException ex = assertThrows(RelayApiException.class, () -> svc.ingest(duplicate));

        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("duplicate_reading", ex.errorCode());
        assertEquals(1, store.pendingReadings());
    }

    @Test
    void ingestShouldReleaseReplayKeyOnRateLimitSoRetryIsNotDuplicate() {
        InMemoryEpochIngressWindow store = new InMemoryEpochIngressWindow(new SimpleMeterRegistry());
        // Rate limit window of 300s — both requests arrive at the same "now", so the
        // second one is rate-limited.
        ReadingIngestionService svc = new ReadingIngestionService(
                relayProperties(false, false),
                request -> true,
                reporter -> true,
                store
        );

        long now = Instant.now().getEpochSecond();
        String reporter = "0xdddddddddddddddddddddddddddddddddddddddd";

        // first request for cell1 accepted → key claimed + enqueued
        svc.ingest(validRequest(reporter, now));
        assertEquals(1, store.pendingReadings());

        // second request for cell2 by the same reporter — passes replay guard (different cell)
        // but hits rate limit; the key for cell2 must be released
        ReadingSubmissionRequest cell2 = new ReadingSubmissionRequest(
                reporter, "0x0882830a2fffffff",
                now / 3600, now, 120, 350, null, null, null, null, null, signature());
        assertThrows(RelayApiException.class, () -> svc.ingest(cell2));
        assertEquals(1, store.pendingReadings()); // cell2 must NOT be in queue

        // simulate reporter waiting out the rate-limit window by using a fresh service
        // (same store, new rate-limit state) — cell2 should now be accepted, not conflict
        ReadingIngestionService fresh = new ReadingIngestionService(
                relayProperties(false, false),
                request -> true,
                reporter2 -> true,
                store
        );
        ReadingSubmissionRequest cell2Retry = new ReadingSubmissionRequest(
                reporter, "0x0882830a2fffffff",
                now / 3600, now, 120, 350, null, null, null, null, null, signature());
        fresh.ingest(cell2Retry);
        assertEquals(2, store.pendingReadings()); // cell2 is now accepted
    }

    @Test
    void ingestShouldReleaseReplayKeyOnNoStakeSoRetryIsNotDuplicate() {
        InMemoryEpochIngressWindow store = new InMemoryEpochIngressWindow(new SimpleMeterRegistry());
        long now = Instant.now().getEpochSecond();
        String reporter = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";

        // first attempt: stake check fails → key must be released
        ReadingIngestionService noStakeSvc = new ReadingIngestionService(
                relayProperties(false, true),
                request -> true,
                r -> false,
                store
        );
        assertThrows(RelayApiException.class, () -> noStakeSvc.ingest(validRequest(reporter, now)));
        assertEquals(0, store.pendingReadings()); // must NOT be enqueued

        // second attempt after staking: same cell should be accepted, not conflict
        ReadingIngestionService stakedSvc = new ReadingIngestionService(
                relayProperties(false, true),
                request -> true,
                r -> true,
                store
        );
        stakedSvc.ingest(validRequest(reporter, now));
        assertEquals(1, store.pendingReadings());
    }

    // ── Phase 3 resilience: replay-key poisoning fix and 503 path ─────────────

    @Test
    void stakeQueryExceptionShouldReturn503() {
        // When the stake provider is temporarily unavailable (fail-closed mode surfaced as
        // StakeQueryException), the service should respond with 503 Service Unavailable.
        ReadingIngestionService svc = new ReadingIngestionService(
                relayProperties(false, true),
                request -> true,
                reporter -> { throw new protocol.vayu.relay.service.commit.aggregation.StakeQueryException("rpc down"); }
        );

        RelayApiException ex = assertThrows(RelayApiException.class,
                () -> svc.ingest(validRequest("0xf1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1", Instant.now().getEpochSecond())));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.status());
        assertEquals("service_unavailable", ex.errorCode());
    }

    @Test
    void stakeQueryExceptionShouldNotConsumeReplayKey() {
        // Fix for replay-key poisoning: stake check now runs BEFORE tryClaimReplayKey.
        // A StakeQueryException (temporary RPC outage) must not permanently consume the
        // replay key — the reporter must be able to retry the same submission once the RPC recovers.
        InMemoryEpochIngressWindow store = new InMemoryEpochIngressWindow(new SimpleMeterRegistry());
        long now = Instant.now().getEpochSecond();
        String reporter = "0xf2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2";

        // First attempt: stake RPC throws → 503
        ReadingIngestionService rpcDownSvc = new ReadingIngestionService(
                relayProperties(false, true),
                request -> true,
                r -> { throw new protocol.vayu.relay.service.commit.aggregation.StakeQueryException("rpc unavailable"); },
                store
        );
        assertThrows(RelayApiException.class, () -> rpcDownSvc.ingest(validRequest(reporter, now)));
        assertEquals(0, store.pendingReadings()); // must not be enqueued

        // Second attempt: RPC has recovered → the same reporter+epoch+cell must be accepted
        ReadingIngestionService rpcUpSvc = new ReadingIngestionService(
                relayProperties(false, true),
                request -> true,
                r -> true,
                store
        );
        ReadingAcceptedResponse response = rpcUpSvc.ingest(validRequest(reporter, now));
        assertEquals("accepted", response.status());
        assertEquals(1, store.pendingReadings());
    }

    @Test
    void stakeCheckShouldRunBeforeReplayKeyIsClaimed() {
        // Verify ordering: if validation fails (unauthorized) before the key is claimed,
        // a retry must not be rejected as a duplicate.
        InMemoryEpochIngressWindow store = new InMemoryEpochIngressWindow(new SimpleMeterRegistry());
        long now = Instant.now().getEpochSecond();
        String reporter = "0xf3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3";

        ReadingIngestionService unstaked = new ReadingIngestionService(
                relayProperties(false, true),
                request -> true,
                r -> false,   // stake check returns false (unauthorized)
                store
        );

        // Rejected with 401 — key must NOT have been consumed
        RelayApiException ex = assertThrows(RelayApiException.class,
                () -> unstaked.ingest(validRequest(reporter, now)));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.status());

        // Reporter subsequently stakes and retries — must succeed (not 409 duplicate)
        ReadingIngestionService staked = new ReadingIngestionService(
                relayProperties(false, true),
                request -> true,
                r -> true,
                store
        );
        ReadingAcceptedResponse resp = staked.ingest(validRequest(reporter, now));
        assertEquals("accepted", resp.status());
    }

    @Test
    void ingestShouldAcceptSameReporterDifferentCellsInSameEpoch() {
        InMemoryEpochIngressWindow store = new InMemoryEpochIngressWindow(new SimpleMeterRegistry());
        ReadingIngestionService svc = new ReadingIngestionService(
                relayProperties(false, false),
                request -> true,
                reporter -> true,
                store
        );

        // Verify enqueueIfNotSeen allows (reporter, epoch, cell1) and (reporter, epoch, cell2)
        // independently. We bypass the service-level rate limit by calling enqueueIfNotSeen
        // directly on the store — the rate limit is a time guard, not a cell guard.
        long now = Instant.now().getEpochSecond();
        long epochId = now / 3600;
        ReadingSubmissionRequest cell1 = new ReadingSubmissionRequest(
                "0xcccccccccccccccccccccccccccccccccccccccc", "0x0882830a1fffffff",
                epochId, now, 120, 350, null, null, null, null, null, signature());
        ReadingSubmissionRequest cell2 = new ReadingSubmissionRequest(
                "0xcccccccccccccccccccccccccccccccccccccccc", "0x0882830a2fffffff",
                epochId, now, 130, 360, null, null, null, null, null, signature());

        String key1 = "0xcccccccccccccccccccccccccccccccccccccccc:" + epochId + ":0x0882830a1fffffff";
        String key2 = "0xcccccccccccccccccccccccccccccccccccccccc:" + epochId + ":0x0882830a2fffffff";

        assertTrue(store.enqueueIfNotSeen(cell1, key1));
        assertTrue(store.enqueueIfNotSeen(cell2, key2));
        assertEquals(2, store.pendingReadings());
    }

    private ReadingSubmissionRequest validRequest(String reporter, long timestamp) {
        return new ReadingSubmissionRequest(
                reporter,
                "0x0882830a1fffffff",
                timestamp / 3600,
            timestamp,
                120,
                350,
                null,
                null,
                null,
                null,
                null,
                signature()
        );
    }

    private static String signature() {
        return "0x111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111b";
    }

    private static RelayProperties relayProperties(boolean signatureVerificationEnabled, boolean stakeCheckEnabled) {
        RelayProperties.Messages messages = new RelayProperties.Messages(
                "aqi must be greater than %d",
                "pm25 must be greater than %d",
                "timestamp is required",
                "timestamp is outside allowed tolerance window",
            "epochId does not match timestamp and epoch duration",
                "h3Index must be a 64-bit hex string",
                "h3Index must be valid hex",
                "h3Index resolution must be %d",
                "reporter can submit once every %d seconds"
        );

        RelayProperties.Validation validation = new RelayProperties.Validation(
                8,
                300,
                1,
                1,
                messages
        );

        RelayProperties.Epoch epoch = new RelayProperties.Epoch(3600, 60000, 300, 3, 50, new java.math.BigInteger("684931506849315068493"));
        RelayProperties.Eip712 eip712 = new RelayProperties.Eip712(
            "VayuProtocol",
            "1",
            84532,
            "0x0000000000000000000000000000000000000000"
        );
        RelayProperties.Security security = new RelayProperties.Security(
            signatureVerificationEnabled,
            stakeCheckEnabled,
            eip712,
            null
        );
        return new RelayProperties(epoch, validation, security, null, null);
    }

    // ── Metrics wiring tests ──────────────────────────────────────────────────

    @Test
    void metricsShouldRecordAcceptedOnSuccess() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RelayMetrics metrics = new RelayMetrics(registry);
        ReadingIngestionService svc = new ReadingIngestionService(
                relayProperties(false, false),
                request -> true,
                reporter -> true,
                new InMemoryEpochIngressWindow(new SimpleMeterRegistry()),
                metrics);

        long now = Instant.now().getEpochSecond();
        svc.ingest(validRequest("0xaaaa000000000000000000000000000000000001", now));

        assertEquals(1.0, registry.counter("vayu.readings.accepted").count());
        assertEquals(0.0, registry.counter("vayu.readings.rejected", "reason", "validation_error").count());
    }

    @Test
    void metricsShouldRecordValidationErrorOnBadAqi() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RelayMetrics metrics = new RelayMetrics(registry);
        ReadingIngestionService svc = new ReadingIngestionService(
                relayProperties(false, false),
                request -> true,
                reporter -> true,
                new InMemoryEpochIngressWindow(new SimpleMeterRegistry()),
                metrics);

        long now = Instant.now().getEpochSecond();
        ReadingSubmissionRequest bad = new ReadingSubmissionRequest(
                "0xaaaa000000000000000000000000000000000002",
                "0x0882830a1fffffff",
                now / 3600, now,
                0, 350, null, null, null, null, null,
                signature());

        assertThrows(RelayApiException.class, () -> svc.ingest(bad));
        assertEquals(1.0, registry.counter("vayu.readings.rejected", "reason", "validation_error").count());
        assertEquals(0.0, registry.counter("vayu.readings.accepted").count());
    }

    @Test
    void metricsShouldRecordStaleTsOnOldTimestamp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RelayMetrics metrics = new RelayMetrics(registry);
        ReadingIngestionService svc = new ReadingIngestionService(
                relayProperties(false, false),
                request -> true,
                reporter -> true,
                new InMemoryEpochIngressWindow(new SimpleMeterRegistry()),
                metrics);

        long stale = Instant.now().minusSeconds(1000).getEpochSecond();
        assertThrows(RelayApiException.class, () -> svc.ingest(validRequest("0xaaaa000000000000000000000000000000000003", stale)));
        assertEquals(1.0, registry.counter("vayu.readings.rejected", "reason", "stale_timestamp").count());
    }

    @Test
    void metricsShouldRecordWrongEpochOnMismatch() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RelayMetrics metrics = new RelayMetrics(registry);
        ReadingIngestionService svc = new ReadingIngestionService(
                relayProperties(false, false),
                request -> true,
                reporter -> true,
                new InMemoryEpochIngressWindow(new SimpleMeterRegistry()),
                metrics);

        long now = Instant.now().getEpochSecond();
        long wrongEpoch = (now / 3600) + 99;
        ReadingSubmissionRequest bad = new ReadingSubmissionRequest(
                "0xaaaa000000000000000000000000000000000004",
                "0x0882830a1fffffff",
                wrongEpoch, now,
                120, 350, null, null, null, null, null,
                signature());

        assertThrows(RelayApiException.class, () -> svc.ingest(bad));
        assertEquals(1.0, registry.counter("vayu.readings.rejected", "reason", "wrong_epoch").count());
    }

    @Test
    void metricsShouldRecordWrongResolutionOnBadH3() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RelayMetrics metrics = new RelayMetrics(registry);
        ReadingIngestionService svc = new ReadingIngestionService(
                relayProperties(false, false),
                request -> true,
                reporter -> true,
                new InMemoryEpochIngressWindow(new SimpleMeterRegistry()),
                metrics);

        long now = Instant.now().getEpochSecond();
        ReadingSubmissionRequest bad = new ReadingSubmissionRequest(
                "0xaaaa000000000000000000000000000000000005",
                "0x0872830a1fffffff",
                now / 3600, now,
                120, 350, null, null, null, null, null,
                signature());

        assertThrows(RelayApiException.class, () -> svc.ingest(bad));
        assertEquals(1.0, registry.counter("vayu.readings.rejected", "reason", "wrong_resolution").count());
    }

    @Test
    void metricsShouldRecordInvalidSigOnFailedVerification() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RelayMetrics metrics = new RelayMetrics(registry);
        ReadingIngestionService svc = new ReadingIngestionService(
                relayProperties(true, false),
                request -> false,
                reporter -> true,
                new InMemoryEpochIngressWindow(new SimpleMeterRegistry()),
                metrics);

        long now = Instant.now().getEpochSecond();
        assertThrows(RelayApiException.class, () -> svc.ingest(validRequest("0xaaaa000000000000000000000000000000000006", now)));
        assertEquals(1.0, registry.counter("vayu.readings.rejected", "reason", "invalid_signature").count());
    }

    @Test
    void metricsShouldRecordDuplicateOnReplay() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RelayMetrics metrics = new RelayMetrics(registry);
        InMemoryEpochIngressWindow store = new InMemoryEpochIngressWindow(new SimpleMeterRegistry());
        ReadingIngestionService svc = new ReadingIngestionService(
                relayProperties(false, false),
                request -> true,
                reporter -> true,
                store,
                metrics);

        long now = Instant.now().getEpochSecond();
        String reporter = "0xaaaa000000000000000000000000000000000007";
        svc.ingest(validRequest(reporter, now));
        assertThrows(RelayApiException.class, () -> svc.ingest(validRequest(reporter, now)));

        assertEquals(1.0, registry.counter("vayu.readings.accepted").count());
        assertEquals(1.0, registry.counter("vayu.readings.rejected", "reason", "duplicate").count());
    }

    @Test
    void metricsShouldRecordRateLimitedAfterFirstAccepted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RelayMetrics metrics = new RelayMetrics(registry);
        InMemoryEpochIngressWindow store = new InMemoryEpochIngressWindow(new SimpleMeterRegistry());
        ReadingIngestionService svc = new ReadingIngestionService(
                relayProperties(false, false),
                request -> true,
                reporter -> true,
                store,
                metrics);

        long now = Instant.now().getEpochSecond();
        String reporter = "0xaaaa000000000000000000000000000000000008";
        svc.ingest(validRequest(reporter, now));

        // Different cell to bypass replay guard, same reporter to trigger rate limit
        ReadingSubmissionRequest cell2 = new ReadingSubmissionRequest(
                reporter, "0x0882830a2fffffff",
                now / 3600, now,
                120, 350, null, null, null, null, null,
                signature());
        assertThrows(RelayApiException.class, () -> svc.ingest(cell2));

        assertEquals(1.0, registry.counter("vayu.readings.accepted").count());
        assertEquals(1.0, registry.counter("vayu.readings.rejected", "reason", "rate_limited").count());
    }

    @Test
    void metricsShouldRecordNoStakeWhenStakeCheckFails() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RelayMetrics metrics = new RelayMetrics(registry);
        ReadingIngestionService svc = new ReadingIngestionService(
                relayProperties(false, true),
                request -> true,
                reporter -> false,
                new InMemoryEpochIngressWindow(new SimpleMeterRegistry()),
                metrics);

        long now = Instant.now().getEpochSecond();
        assertThrows(RelayApiException.class, () -> svc.ingest(validRequest("0xaaaa000000000000000000000000000000000009", now)));
        assertEquals(1.0, registry.counter("vayu.readings.rejected", "reason", "no_stake").count());
    }
}
