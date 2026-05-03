package protocol.vayu.relay.service.commit.ipfs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PinataIpfsPinClientTest {

    private static final String JWT = "test.jwt.token";
    private static final long EPOCH_ID = 100L;
    private static final String JSON_BLOB = "{\"epochId\":100,\"totalReadings\":30}";
    private static final String PINATA_RESPONSE =
            "{\"IpfsHash\":\"bafyrei123abc\",\"PinSize\":1234,\"Timestamp\":\"2026-05-03T00:00:00Z\"}";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private PinataIpfsPinClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new PinataIpfsPinClient(JWT, restTemplate);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void pinShouldReturnCidFromIpfsHashField() {
        server.expect(requestTo(PinataIpfsPinClient.ENDPOINT))
                .andExpect(method(Objects.requireNonNull(HttpMethod.POST)))
                .andRespond(withSuccess(PINATA_RESPONSE, MediaType.APPLICATION_JSON));

        String cid = client.pin(EPOCH_ID, JSON_BLOB);

        assertEquals("bafyrei123abc", cid);
        server.verify();
    }

    @Test
    void pinShouldSendBearerAuthorizationHeader() {
        server.expect(requestTo(PinataIpfsPinClient.ENDPOINT))
                .andExpect(method(Objects.requireNonNull(HttpMethod.POST)))
                .andExpect(header("Authorization", "Bearer " + JWT))
                .andRespond(withSuccess(PINATA_RESPONSE, MediaType.APPLICATION_JSON));

        client.pin(EPOCH_ID, JSON_BLOB);
        server.verify();
    }

    @Test
    void pinShouldSendJsonContentTypeHeader() {
        server.expect(requestTo(PinataIpfsPinClient.ENDPOINT))
                .andExpect(method(Objects.requireNonNull(HttpMethod.POST)))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess(PINATA_RESPONSE, MediaType.APPLICATION_JSON));

        client.pin(EPOCH_ID, JSON_BLOB);
        server.verify();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error cases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void pinShouldThrowIpfsPinExceptionOnHttp500() {
        server.expect(requestTo(PinataIpfsPinClient.ENDPOINT))
                .andRespond(withServerError());

        assertThrows(IpfsPinException.class, () -> client.pin(EPOCH_ID, JSON_BLOB));
        server.verify();
    }

    @Test
    void pinShouldThrowIpfsPinExceptionOnHttp401() {
        server.expect(requestTo(PinataIpfsPinClient.ENDPOINT))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("{\"error\":{\"reason\":\"INVALID_JWT\"}}"));

        assertThrows(IpfsPinException.class, () -> client.pin(EPOCH_ID, JSON_BLOB));
        server.verify();
    }

    @Test
    void pinShouldThrowIpfsPinExceptionOnHttp429RateLimit() {
        server.expect(requestTo(PinataIpfsPinClient.ENDPOINT))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .body("{\"error\":{\"reason\":\"RATE_LIMIT_EXCEEDED\"}}"));

        assertThrows(IpfsPinException.class, () -> client.pin(EPOCH_ID, JSON_BLOB));
        server.verify();
    }

    @Test
    void pinShouldThrowWhenIpfsHashFieldIsMissing() {
        String responseWithoutHash = "{\"PinSize\":1234,\"Timestamp\":\"2026-05-03T00:00:00Z\"}";
        server.expect(requestTo(PinataIpfsPinClient.ENDPOINT))
                .andRespond(withSuccess(responseWithoutHash, MediaType.APPLICATION_JSON));

        assertThrows(IpfsPinException.class, () -> client.pin(EPOCH_ID, JSON_BLOB));
        server.verify();
    }

    @Test
    void pinShouldThrowWhenIpfsHashIsBlank() {
        String responseWithBlankHash = "{\"IpfsHash\":\"\",\"PinSize\":0}";
        server.expect(requestTo(PinataIpfsPinClient.ENDPOINT))
                .andRespond(withSuccess(responseWithBlankHash, MediaType.APPLICATION_JSON));

        assertThrows(IpfsPinException.class, () -> client.pin(EPOCH_ID, JSON_BLOB));
        server.verify();
    }

    @Test
    void pinShouldThrowWhenBlobIsInvalidJson() {
        // An invalid JSON blob should fail before the HTTP call is even attempted
        assertThrows(IpfsPinException.class, () -> client.pin(EPOCH_ID, "not-json"));
    }
}
