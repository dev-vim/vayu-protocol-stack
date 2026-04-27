package protocol.vayu.relay.service.ingestion;

import org.junit.jupiter.api.Test;
import protocol.vayu.relay.api.dto.ReadingAcceptedResponse;
import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import protocol.vayu.relay.api.error.RelayApiException;
import protocol.vayu.relay.config.RelayProperties;
import protocol.vayu.relay.service.commit.InMemoryEpochReadingStore;
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
        InMemoryEpochReadingStore store = new InMemoryEpochReadingStore();
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

        RelayProperties.Epoch epoch = new RelayProperties.Epoch(3600, 60000, 300);
        RelayProperties.Eip712 eip712 = new RelayProperties.Eip712(
            "VayuProtocol",
            "1",
            84532,
            "0x0000000000000000000000000000000000000000"
        );
        RelayProperties.Security security = new RelayProperties.Security(
            signatureVerificationEnabled,
            stakeCheckEnabled,
            eip712
        );
        return new RelayProperties(epoch, validation, security);
    }
}
