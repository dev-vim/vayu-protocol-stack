package protocol.vayu.relay.service.commit.ipfs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Pins epoch blobs to a local Kubo (go-ipfs) node via its HTTP RPC API.
 *
 * API used: {@code POST /api/v0/add}
 * Response: newline-delimited JSON; the first object contains the CID as {@code Hash}.
 *
 * Intended for local development and CI. Run Kubo with:
 *   {@code docker run -d -p 5001:5001 ipfs/kubo:latest}
 */
public class KuboIpfsPinClient implements IpfsPinClient {

    private static final Logger LOG = LoggerFactory.getLogger(KuboIpfsPinClient.class);

    private final String apiUrl;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public KuboIpfsPinClient(String apiUrl, RestTemplate restTemplate) {
        this.apiUrl = apiUrl;
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String pin(long epochId, String jsonBlob) {
        byte[] content = jsonBlob.getBytes(StandardCharsets.UTF_8);
        String filename = "epoch-" + epochId + ".json";

        // Kubo /api/v0/add expects multipart/form-data
        ByteArrayResource fileResource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpEntity<LinkedMultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            String raw = restTemplate.postForObject(apiUrl + "/api/v0/add", request, String.class);
            if (raw == null || raw.isBlank()) {
                throw new IpfsPinException("Kubo returned empty response for epoch " + epochId);
            }
            // Kubo returns NDJSON; parse the first (and usually only) line
            String firstLine = raw.lines().filter(l -> !l.isBlank()).findFirst()
                    .orElseThrow(() -> new IpfsPinException("Kubo response has no JSON lines: " + raw));
            Map<?, ?> parsed = objectMapper.readValue(firstLine, Map.class);
            String hash = (String) parsed.get("Hash");
            if (hash == null || hash.isBlank()) {
                throw new IpfsPinException("Kubo response missing Hash field: " + firstLine);
            }
            LOG.debug("epoch {} pinned via Kubo: {}", epochId, hash);
            return hash;
        } catch (JsonProcessingException e) {
            throw new IpfsPinException("Failed to parse Kubo /api/v0/add response for epoch " + epochId, e);
        } catch (RestClientException e) {
            throw new IpfsPinException("Kubo HTTP error pinning epoch " + epochId, e);
        }
    }
}
