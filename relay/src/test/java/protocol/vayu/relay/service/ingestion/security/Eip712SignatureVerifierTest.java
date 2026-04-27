package protocol.vayu.relay.service.ingestion.security;

import org.junit.jupiter.api.Test;
import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import protocol.vayu.relay.config.RelayProperties;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.crypto.Sign.SignatureData;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Eip712SignatureVerifierTest {

    @Test
    void verifyShouldAcceptValidSignature() throws Exception {
        ECKeyPair keyPair = ECKeyPair.create(new BigInteger("1"));
        String reporter = "0x" + Keys.getAddress(keyPair.getPublicKey());

        RelayProperties props = relayProperties();
        Eip712SignatureVerifier verifier = new Eip712SignatureVerifier(props);

        ReadingSubmissionRequest unsigned = request(reporter, null, null, null, null, null);
        String signature = sign(verifier, unsigned, keyPair);
        ReadingSubmissionRequest signed = withSignature(unsigned, signature);

        assertTrue(verifier.verify(signed));
    }

    @Test
    void verifyShouldRejectReporterMismatch() throws Exception {
        ECKeyPair keyPair = ECKeyPair.create(new BigInteger("2"));
        String signerReporter = "0x" + Keys.getAddress(keyPair.getPublicKey());

        RelayProperties props = relayProperties();
        Eip712SignatureVerifier verifier = new Eip712SignatureVerifier(props);

        ReadingSubmissionRequest unsigned = request(signerReporter, 1, 2, 3, 4, 5);
        String signature = sign(verifier, unsigned, keyPair);

        ReadingSubmissionRequest mismatched = new ReadingSubmissionRequest(
                "0x1111111111111111111111111111111111111111",
                unsigned.h3Index(),
            unsigned.epochId(),
            unsigned.timestamp(),
                unsigned.aqi(),
                unsigned.pm25(),
                unsigned.pm10(),
                unsigned.o3(),
                unsigned.no2(),
                unsigned.so2(),
                unsigned.co(),
                signature
        );

        assertFalse(verifier.verify(mismatched));
    }

    @Test
    void verifyShouldTreatNullOptionalPollutantsAsZero() throws Exception {
        ECKeyPair keyPair = ECKeyPair.create(new BigInteger("3"));
        String reporter = "0x" + Keys.getAddress(keyPair.getPublicKey());

        RelayProperties props = relayProperties();
        Eip712SignatureVerifier verifier = new Eip712SignatureVerifier(props);

        ReadingSubmissionRequest zeros = request(reporter, 0, 0, 0, 0, 0);
        String signature = sign(verifier, zeros, keyPair);

        ReadingSubmissionRequest nulls = request(reporter, null, null, null, null, null);
        ReadingSubmissionRequest signedNulls = withSignature(nulls, signature);

        assertTrue(verifier.verify(signedNulls));
    }

    @Test
    void verifyShouldRejectMalformedSignature() {
        RelayProperties props = relayProperties();
        Eip712SignatureVerifier verifier = new Eip712SignatureVerifier(props);

        ReadingSubmissionRequest malformed = new ReadingSubmissionRequest(
                "0x1111111111111111111111111111111111111111",
                "0x0882830a1fffffff",
            Instant.now().getEpochSecond() / 3600,
            Instant.now().getEpochSecond(),
                150,
                350,
                null,
                null,
                null,
                null,
                null,
                "0x1234"
        );

        assertFalse(verifier.verify(malformed));
    }

    private String sign(Eip712SignatureVerifier verifier, ReadingSubmissionRequest request, ECKeyPair keyPair) {
        byte[] digest = verifier.buildDigest(request);
        SignatureData sig = Sign.signMessage(digest, keyPair, false);

        byte[] full = new byte[65];
        System.arraycopy(sig.getR(), 0, full, 0, 32);
        System.arraycopy(sig.getS(), 0, full, 32, 32);
        full[64] = sig.getV()[0];
        return Numeric.toHexString(full);
    }

    private ReadingSubmissionRequest withSignature(ReadingSubmissionRequest request, String signature) {
        return new ReadingSubmissionRequest(
                request.reporter(),
                request.h3Index(),
            request.epochId(),
            request.timestamp(),
                request.aqi(),
                request.pm25(),
                request.pm10(),
                request.o3(),
                request.no2(),
                request.so2(),
                request.co(),
                signature
        );
    }

    private ReadingSubmissionRequest request(
            String reporter,
            Integer pm10,
            Integer o3,
            Integer no2,
            Integer so2,
            Integer co
    ) {
        return new ReadingSubmissionRequest(
                reporter,
                "0x0882830a1fffffff",
            Instant.now().getEpochSecond() / 3600,
            Instant.now().getEpochSecond(),
                150,
                350,
                pm10,
                o3,
                no2,
                so2,
                co,
                "0x" + "0".repeat(130)
        );
    }

    private RelayProperties relayProperties() {
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
        RelayProperties.Security security = new RelayProperties.Security(true, false, eip712);

        return new RelayProperties(epoch, validation, security);
    }
}
