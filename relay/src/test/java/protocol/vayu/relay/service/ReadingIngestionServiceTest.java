package protocol.vayu.relay.service;

import org.junit.jupiter.api.Test;
import protocol.vayu.relay.api.dto.ReadingAcceptedResponse;
import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import protocol.vayu.relay.api.error.RelayApiException;
import protocol.vayu.relay.config.RelayProperties;
import org.springframework.http.HttpStatus;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadingIngestionServiceTest {

    private final ReadingIngestionService service = new ReadingIngestionService(relayProperties());

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
                0,
                300,
                null,
                null,
                null,
                null,
                null,
                Instant.now().getEpochSecond(),
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
                120,
                350,
                null,
                null,
                null,
                null,
                null,
                Instant.now().getEpochSecond(),
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

    private ReadingSubmissionRequest validRequest(String reporter, long timestamp) {
        return new ReadingSubmissionRequest(
                reporter,
                "0x0882830a1fffffff",
                120,
                350,
                null,
                null,
                null,
                null,
                null,
                timestamp,
                signature()
        );
    }

    private static String signature() {
        return "0x111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111b";
    }

    private static RelayProperties relayProperties() {
        RelayProperties.Messages messages = new RelayProperties.Messages(
                "aqi must be greater than %d",
                "pm25 must be greater than %d",
                "timestamp is required",
                "timestamp is outside allowed tolerance window",
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
        return new RelayProperties(epoch, validation);
    }
}
