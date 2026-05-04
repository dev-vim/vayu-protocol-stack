package protocol.vayu.relay.config;

import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import protocol.vayu.relay.service.commit.aggregation.StakeWeightProvider;
import protocol.vayu.relay.service.commit.aggregation.UniformStakeWeightProvider;
import protocol.vayu.relay.service.commit.aggregation.Web3jStakeWeightProvider;

@Configuration
public class ChainConfig {

    /**
     * Live stake provider: reads VayuToken.balanceOf via eth_call.
     * Active when relay.security.stake-check-enabled=true.
     */
    @Bean
    @ConditionalOnProperty(name = "relay.security.stake-check-enabled", havingValue = "true")
    public StakeWeightProvider web3jStakeWeightProvider(RelayProperties props) {
        Web3j web3j = Web3j.build(new HttpService(props.chain().rpcUrl()));
        return new Web3jStakeWeightProvider(web3j, props.chain().settlementAddress());
    }

    /**
     * Fallback stake provider: uniform weight (1) for every reporter.
     * Active when relay.security.stake-check-enabled=false (default).
     */
    @Bean
    @ConditionalOnProperty(
            name = "relay.security.stake-check-enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    public StakeWeightProvider uniformStakeWeightProvider() {
        return new UniformStakeWeightProvider();
    }
}
