package protocol.vayu.relay.service.commit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KuboIpfsPinClientTest {

    private static final String KUBO_URL = "http://localhost:5001";
    private static final String ADD_ENDPOINT = KUBO_URL + "/api/v0/add";
    private static final long EPOCH_ID = 42L;
    private static final String JSON_BLOB = "{\"epochId\":42,\"totalReadings\":10}";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private KuboIpfsPinClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new KuboIpfsPinClient(KUBO_URL, restTemplate);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void pinShouldReturnCidFromHashField() {
        String kuboResponse = "{\"Name\":\"epoch-42.json\",\"Hash\":\"QmTestCidAbc123\",\"Size\":\"42\"}";
        server.expect(requestTo(ADD_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(kuboResponse, MediaType.APPLICATION_JSON));

        String cid = client.pin(EPOCH_ID, JSON_BLOB);

        assertEquals("QmTestCidAbc123", cid);
        server.verify();
    }

    @Test
    void pinShouldHandleV1BafyCid() {
        String cid = "bafkreidivzimqfqtoqsvtkpvne6wieacgg2qtd3gkbktzbike4b7f5bfii";
        String kuboResponse = "{\"Name\":\"epoch-42.json\",\"Hash\":\"" + cid + "\",\"Size\":\"1234\"}";
        server.expect(requestTo(ADD_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(kuboResponse, MediaType.APPLICATION_JSON));

        assertEquals(cid, client.pin(EPOCH_ID, JSON_BLOB));
        server.verify();
    }

    @Test
    void pinShouldThrowWhenFirstNdjsonLineHasEmptyHash() {
        // Kubo normally emits exactly one line per single-file add. If a multi-line response
        // arrives whose first line has an empty Hash, the client throws rather than silently
        // skipping to the second line — avoiding use of a bad CID.
        String ndjsonResponse = "{\"Name\":\"\",\"Hash\":\"\",\"Size\":\"0\"}\n"
                + "{\"Name\":\"epoch-42.json\",\"Hash\":\"QmCorrectCid\",\"Size\":\"42\"}\n";
        server.expect(requestTo(ADD_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(ndjsonResponse, MediaType.APPLICATION_JSON));

        assertThrows(IpfsPinException.class, () -> client.pin(EPOCH_ID, JSON_BLOB));
        server.verify();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error cases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void pinShouldThrowIpfsPinExceptionOnHttpServerError() {
        server.expect(requestTo(ADD_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThrows(IpfsPinException.class, () -> client.pin(EPOCH_ID, JSON_BLOB));
        server.verify();
    }

    @Test
    void pinShouldThrowIpfsPinExceptionOnHttp400() {
        server.expect(requestTo(ADD_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"Message\":\"invalid body\"}"));

        assertThrows(IpfsPinException.class, () -> client.pin(EPOCH_ID, JSON_BLOB));
        server.verify();
    }

    @Test
    void pinShouldThrowWhenResponseBodyIsEmpty() {
        server.expect(requestTo(ADD_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThrows(IpfsPinException.class, () -> client.pin(EPOCH_ID, JSON_BLOB));
        server.verify();
    }

    @Test
    void pinShouldThrowWhenHashFieldIsMissingFromResponse() {
        String responseWithoutHash = "{\"Name\":\"epoch-42.json\",\"Size\":\"42\"}";
        server.expect(requestTo(ADD_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseWithoutHash, MediaType.APPLICATION_JSON));

        assertThrows(IpfsPinException.class, () -> client.pin(EPOCH_ID, JSON_BLOB));
        server.verify();
    }

    @Test
    void pinShouldThrowWhenResponseIsNotJson() {
        server.expect(requestTo(ADD_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("not json", MediaType.TEXT_PLAIN));

        assertThrows(IpfsPinException.class, () -> client.pin(EPOCH_ID, JSON_BLOB));
        server.verify();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Endpoint construction
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void pinShouldUseConfiguredApiUrlAsBase() {
        // Verify the client appends the correct Kubo RPC path
        String customUrl = "http://ipfs-node.internal:5001";
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer customServer = MockRestServiceServer.createServer(rt);
        KuboIpfsPinClient customClient = new KuboIpfsPinClient(customUrl, rt);

        customServer.expect(requestTo(customUrl + "/api/v0/add"))
                .andRespond(withSuccess("{\"Hash\":\"QmCustom\"}", MediaType.APPLICATION_JSON));

        assertEquals("QmCustom", customClient.pin(EPOCH_ID, JSON_BLOB));
        customServer.verify();
    }
}
