package protocol.vayu.relay.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import protocol.vayu.relay.service.ingestion.security.Eip712SignatureVerifier;
import protocol.vayu.relay.service.ingestion.security.TestEip712Signer;

import java.time.Instant;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code POST /v1/readings} with EIP-712 signature verification
 * enabled. Runs against the {@code sig-enabled} Spring profile which sets:
 * <ul>
 *   <li>{@code relay.security.signature-verification-enabled=true}</li>
 *   <li>{@code relay.security.eip712.chain-id=31337} (local Anvil)</li>
 *   <li>{@code relay.security.eip712.verifying-contract=0x9fE46736…} (deterministic deploy)</li>
 *   <li>{@code relay.epoch.duration-seconds=60}</li>
 * </ul>
 *
 * <p>Private keys are Anvil deterministic test accounts — safe for local dev/CI only.
 *
 * <p>The pipeline enforces signature verification <em>before</em> rate limiting, so a
 * failed-signature request never consumes the claimed reporter's rate-limit slot. The
 * {@code poisonedRateLimitShouldNotBlockLegitimateReporter} test validates this property
 * directly. Each test still uses a distinct reporter address to prevent cross-test
 * rate-limit collisions inside the shared Spring application context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("sig-enabled")
class RelayControllerSigEnabledTest {

    // Anvil account 1 — reporter-1
    private static final String REPORTER_1_KEY  = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d";
    private static final String REPORTER_1_ADDR = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

    // Anvil account 2 — reporter-2
    private static final String REPORTER_2_KEY  = "0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a";
    private static final String REPORTER_2_ADDR = "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC";

    // Anvil account 3 — reporter-3 (address only; used as the claimed identity in the wrong-signer test)
    private static final String REPORTER_3_ADDR = "0x90F79bf6EB2c4f870365E785982E1f101E93b906";

    // Anvil account 4 — reporter-4 (victim in the rate-limit poisoning test)
    private static final String REPORTER_4_KEY  = "0x47e179ec197488593b187f80a00eb0da91f1b9d0b13f8733639f19c30a34926a";
    private static final String REPORTER_4_ADDR = "0x15d34AAf54267DB7D7c367839AAf71A00a2C6A65";

    // Anvil account 5 — reporter-5 (rate-limit fires on repeated valid submissions)
    private static final String REPORTER_5_KEY  = "0x8b3a350cf5c34c9194ca85829a2df0ec3153be0318b5e2d3348e872092edffba";
    private static final String REPORTER_5_ADDR = "0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc";

    private static final String H3_CELL       = "0x0882830a1fffffff";
    private static final int    EPOCH_DURATION = 60; // matches application-sig-enabled.yml

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Eip712SignatureVerifier verifier;

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void validSignatureShouldBeAccepted() throws Exception {
        long now = Instant.now().getEpochSecond();
        ReadingSubmissionRequest req = unsignedRequest(REPORTER_1_ADDR, now);
        String signature = signer().sign(req, REPORTER_1_KEY);

        mockMvc.perform(post("/v1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(req, signature)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));
    }

    // ── Rejection cases ───────────────────────────────────────────────────────

    @Test
    void tamperedSignatureShouldBeRejected() throws Exception {
        long now = Instant.now().getEpochSecond();
        ReadingSubmissionRequest req = unsignedRequest(REPORTER_2_ADDR, now);
        String signature = tamper(signer().sign(req, REPORTER_2_KEY));

        mockMvc.perform(post("/v1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(req, signature)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void wrongSignerShouldBeRejected() throws Exception {
        // Payload claims reporter-3 as the submitter, but is signed by reporter-2's key.
        long now = Instant.now().getEpochSecond();
        ReadingSubmissionRequest req = unsignedRequest(REPORTER_3_ADDR, now);
        String signature = signer().sign(req, REPORTER_2_KEY); // wrong key for reporter-3

        mockMvc.perform(post("/v1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(req, signature)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    // ── Ordering guarantees ────────────────────────────────────────────────────

    /**
     * An attacker who submits a valid-looking payload bearing a victim's reporter address
     * but signed with the wrong key must NOT consume the victim's rate-limit slot.
     * Under the old pipeline order (rate limit before sig) this would have been a
     * denial-of-service vector; the reorder to sig-first closes it.
     */
    @Test
    void poisonedRateLimitShouldNotBlockLegitimateReporter() throws Exception {
        long now = Instant.now().getEpochSecond();

        // Attacker: claims to be reporter-4 but signs with reporter-2's key.
        ReadingSubmissionRequest poisonReq = unsignedRequest(REPORTER_4_ADDR, now);
        String poisonSig = signer().sign(poisonReq, REPORTER_2_KEY);

        mockMvc.perform(post("/v1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(poisonReq, poisonSig)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));

        // Legitimate reporter-4 must still be able to submit — slot was not consumed.
        ReadingSubmissionRequest legitReq = unsignedRequest(REPORTER_4_ADDR, now);
        String legitSig = signer().sign(legitReq, REPORTER_4_KEY);

        mockMvc.perform(post("/v1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(legitReq, legitSig)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));
    }

    /**
     * After the pipeline reorder, rate limiting still fires for reporters who submit
     * repeatedly with valid signatures within the configured window.
     */
    @Test
    void repeatedValidSubmissionShouldBeRateLimited() throws Exception {
        long now = Instant.now().getEpochSecond();
        ReadingSubmissionRequest req = unsignedRequest(REPORTER_5_ADDR, now);
        String signature = signer().sign(req, REPORTER_5_KEY);
        String body = payload(req, signature);

        mockMvc.perform(post("/v1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Second submission uses a different cell so the replay guard doesn't fire first —
        // we are testing the rate limiter, not dedup.
        ReadingSubmissionRequest req2 = new ReadingSubmissionRequest(
                REPORTER_5_ADDR, "0x0882830a2fffffff",
                now / EPOCH_DURATION, now,
                req.aqi(), req.pm25(),
                null, null, null, null, null,
                "0x" + "0".repeat(130)
        );
        String sig2 = signer().sign(req2, REPORTER_5_KEY);
        String body2 = payload(req2, sig2);

        mockMvc.perform(post("/v1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body2))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("rate_limited"))
                .andExpect(jsonPath("$.retryAfter").value(greaterThan(0)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ReadingSubmissionRequest unsignedRequest(String reporter, long now) {
        return new ReadingSubmissionRequest(
                reporter,
                H3_CELL,
                now / EPOCH_DURATION,
                now,
                42,
                15,
                null, null, null, null, null,
                "0x" + "0".repeat(130)  // placeholder — replaced by sign() before submission
        );
    }

    private TestEip712Signer signer() {
        return new TestEip712Signer(verifier);
    }

    /** Flips the last byte of a hex signature to produce an invalid one. */
    private static String tamper(String signature) {
        byte[] bytes = hexToBytes(signature.substring(2));
        bytes[bytes.length - 1] ^= (byte) 0xFF;
        return "0x" + bytesToHex(bytes);
    }

    private static String payload(ReadingSubmissionRequest req, String signature) {
        return """
                {
                  "reporter":  "%s",
                  "h3Index":   "%s",
                  "epochId":   %d,
                  "timestamp": %d,
                  "aqi":       %d,
                  "pm25":      %d,
                  "signature": "%s"
                }
                """.formatted(
                req.reporter(), req.h3Index(),
                req.epochId(), req.timestamp(),
                req.aqi(), req.pm25(),
                signature);
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
