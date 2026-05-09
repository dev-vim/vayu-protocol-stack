package protocol.vayu.relay.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import protocol.vayu.relay.service.commit.ipfs.IpfsPinClient;
import protocol.vayu.relay.service.commit.ipfs.KuboIpfsPinClient;
import protocol.vayu.relay.service.commit.ipfs.PinataIpfsPinClient;

/**
 * Selects the active {@link IpfsPinClient} implementation based on
 * {@code relay.ipfs.provider}.
 *
 *   provider=kubo    → {@link KuboIpfsPinClient}  (default; local node, dev/CI)
 *   provider=pinata  → {@link PinataIpfsPinClient} (managed service, production)
 */
@Configuration
public class IpfsConfig {

    /**
     * Kubo client — active when {@code relay.ipfs.provider=kubo} or when the
     * property is absent (default for local development).
     */
    @Bean
    @ConditionalOnProperty(name = "relay.ipfs.provider", havingValue = "kubo", matchIfMissing = true)
    IpfsPinClient kuboIpfsPinClient(RelayProperties props) {
        String apiUrl = props.ipfs() != null ? props.ipfs().kuboApiUrl() : "http://localhost:5001";
        return new KuboIpfsPinClient(apiUrl, new RestTemplate());
    }

    /**
     * Pinata client — active only when {@code relay.ipfs.provider=pinata}.
     */
    @Bean
    @ConditionalOnProperty(name = "relay.ipfs.provider", havingValue = "pinata")
    IpfsPinClient pinataIpfsPinClient(RelayProperties props) {
        String endpoint = props.ipfs() != null ? props.ipfs().pinataEndpoint() : "https://api.pinata.cloud/pinning/pinJSONToIPFS";
        String jwt = props.ipfs() != null ? props.ipfs().pinataJwt() : "";
        return new PinataIpfsPinClient(endpoint, jwt, new RestTemplate());
    }
}
