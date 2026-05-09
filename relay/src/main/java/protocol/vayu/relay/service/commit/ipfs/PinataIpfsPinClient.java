package protocol.vayu.relay.service.commit.ipfs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pins epoch blobs to IPFS via the Pinata managed pinning service.
 *
 * Auth:     Bearer JWT supplied via {@code relay.ipfs.pinata-jwt}
 *           (set the {@code RELAY_IPFS_PINATA_JWT} environment variable in production).
 * Endpoint: Configured via {@code relay.ipfs.pinata-endpoint}
 *           (set the {@code RELAY_IPFS_PINATA_ENDPOINT} environment variable in production).
 *
 * Intended for production use. Set {@code relay.ipfs.provider=pinata} to activate.
 */
public class PinataIpfsPinClient implements IpfsPinClient {

    private static final Logger LOG = LoggerFactory.getLogger(PinataIpfsPinClient.class);

    private final String endpoint;
    private final String jwt;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PinataIpfsPinClient(String endpoint, String jwt, RestTemplate restTemplate) {
        this.endpoint = endpoint;
        this.jwt = jwt;
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String pin(long epochId, String jsonBlob) {
        try {
            // Parse the blob so it embeds as a JSON object (not an escaped string)
            Object content = objectMapper.readValue(jsonBlob, Object.class);

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("pinataContent", content);
            requestBody.put("pinataMetadata", Map.of("name", "vayu-epoch-" + epochId));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(Objects.requireNonNull(jwt));

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            Map<?, ?> response = restTemplate.postForObject(endpoint, request, Map.class);
            if (response == null) {
                throw new IpfsPinException("Pinata returned empty response for epoch " + epochId);
            }

            String hash = (String) response.get("IpfsHash");
            if (hash == null || hash.isBlank()) {
                throw new IpfsPinException("Pinata response missing IpfsHash for epoch " + epochId + ": " + response);
            }
            LOG.debug("epoch {} pinned via Pinata: {}", epochId, hash);
            return hash;
        } catch (JsonProcessingException e) {
            throw new IpfsPinException("Failed to process Pinata pin request for epoch " + epochId, e);
        } catch (RestClientException e) {
            throw new IpfsPinException("Pinata HTTP error pinning epoch " + epochId, e);
        }
    }
}
