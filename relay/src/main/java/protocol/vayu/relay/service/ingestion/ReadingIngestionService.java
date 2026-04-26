package protocol.vayu.relay.service.ingestion;

import protocol.vayu.relay.api.dto.ReadingAcceptedResponse;
import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import protocol.vayu.relay.api.error.RelayApiException;
import protocol.vayu.relay.config.RelayProperties;
import protocol.vayu.relay.service.ingestion.security.ReporterStakeChecker;
import protocol.vayu.relay.service.ingestion.security.SignatureVerifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.IllegalFormatException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class ReadingIngestionService {

    private final RelayProperties relayProperties;
    private final SignatureVerifier signatureVerifier;
    private final ReporterStakeChecker reporterStakeChecker;
    private final ConcurrentMap<String, Long> reporterLastReading = new ConcurrentHashMap<>();

    public ReadingIngestionService(
            RelayProperties relayProperties,
            SignatureVerifier signatureVerifier,
            ReporterStakeChecker reporterStakeChecker
    ) {
        this.relayProperties = relayProperties;
        this.signatureVerifier = signatureVerifier;
        this.reporterStakeChecker = reporterStakeChecker;
    }

    public ReadingAcceptedResponse ingest(ReadingSubmissionRequest request) {
        long now = Instant.now().getEpochSecond();

        validateMandatoryFields(request);
        validateTimestampFreshness(request.timestamp(), now);
        validateEpochConsistency(request.epochId(), request.timestamp());
        validateH3Resolution(request.h3Index());
        enforceReporterRateLimit(request.reporter(), now);
        validateSignature(request);
        validateReporterStake(request.reporter());

        return new ReadingAcceptedResponse("accepted", request.epochId(), now);
    }

    private void validateSignature(ReadingSubmissionRequest request) {
        if (!relayProperties.security().signatureVerificationEnabled()) {
            return;
        }

        if (!signatureVerifier.verify(request)) {
            throw RelayApiException.badRequest("invalid signature");
        }
    }

    private void validateReporterStake(String reporter) {
        if (!relayProperties.security().stakeCheckEnabled()) {
            return;
        }

        if (!reporterStakeChecker.hasActiveStake(reporter)) {
            throw RelayApiException.unauthorized("reporter has no active stake");
        }
    }

    private void validateMandatoryFields(ReadingSubmissionRequest request) {
        int minAqi = Math.max(1, relayProperties.validation().minAqi());
        if (request.aqi() == null || request.aqi() < minAqi) {
            throw RelayApiException.badRequest(formatMessage(relayProperties.validation().messages().aqiMin(), minAqi));
        }

        int minPm25 = Math.max(1, relayProperties.validation().minPm25());
        if (request.pm25() == null || request.pm25() < minPm25) {
            throw RelayApiException.badRequest(formatMessage(relayProperties.validation().messages().pm25Min(), minPm25));
        }
    }

    private void validateTimestampFreshness(Long timestamp, long now) {
        if (timestamp == null) {
            throw RelayApiException.badRequest(relayProperties.validation().messages().timestampRequired());
        }

        long tolerance = Math.max(0, relayProperties.epoch().timestampToleranceSeconds());
        if (Math.abs(now - timestamp) > tolerance) {
            throw RelayApiException.badRequest(relayProperties.validation().messages().timestampTolerance());
        }
    }

    private void validateEpochConsistency(Long epochId, Long timestamp) {
        if (epochId == null || timestamp == null) {
            return;
        }

        long epochDuration = Math.max(1, relayProperties.epoch().durationSeconds());
        long expectedEpochId = timestamp / epochDuration;
        if (epochId != expectedEpochId) {
            throw RelayApiException.badRequest(relayProperties.validation().messages().epochIdMismatch());
        }
    }

    private void validateH3Resolution(String h3Index) {
        if (h3Index == null || !h3Index.startsWith("0x") || h3Index.length() != 18) {
            throw RelayApiException.badRequest(relayProperties.validation().messages().h3Format());
        }

        final long h3;
        try {
            h3 = Long.parseUnsignedLong(h3Index.substring(2), 16);
        } catch (NumberFormatException ex) {
            throw RelayApiException.badRequest(relayProperties.validation().messages().h3Hex());
        }

        int resolution = (int) ((h3 >>> 52) & 0x0F);
        int requiredResolution = Math.max(0, Math.min(15, relayProperties.validation().requiredH3Resolution()));
        if (resolution != requiredResolution) {
            throw RelayApiException.badRequest(formatMessage(
                    relayProperties.validation().messages().h3Resolution(),
                    requiredResolution
            ));
        }
    }

    private void enforceReporterRateLimit(String reporter, long now) {
        long rateLimitWindow = Math.max(1, relayProperties.validation().rateLimitWindowSeconds());
        reporterLastReading.compute(reporter, (ignored, lastSeen) -> {
            if (lastSeen != null) {
                long elapsed = now - lastSeen;
                if (elapsed < rateLimitWindow) {
                    int retryAfter = (int) (rateLimitWindow - elapsed);
                    throw RelayApiException.rateLimited(
                            formatMessage(relayProperties.validation().messages().rateLimit(), rateLimitWindow),
                            retryAfter
                    );
                }
            }
            return now;
        });
    }

    private String formatMessage(String template, Object... args) {
        try {
            return String.format(template, args);
        } catch (IllegalFormatException ex) {
            return template;
        }
    }
}
