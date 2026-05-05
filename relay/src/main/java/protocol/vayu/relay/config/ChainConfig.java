package protocol.vayu.relay.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.http.HttpService;
import protocol.vayu.relay.service.commit.aggregation.StakeWeightProvider;
import protocol.vayu.relay.service.commit.aggregation.UniformStakeWeightProvider;
import protocol.vayu.relay.service.commit.aggregation.Web3jStakeWeightProvider;

import java.util.List;

@Configuration
public class ChainConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ChainConfig.class);

    /**
     * Startup guard: calls {@code isActiveRelay(address)} on the settlement contract and
     * aborts startup if the relay wallet is not registered.
     * <p>
     * Only active when {@code relay.chain.on-chain-commit-enabled=true} — in dev/CI mode
     * (the default) no chain is present so the check is skipped entirely.
     */
    @Bean
    @ConditionalOnProperty(name = "relay.chain.on-chain-commit-enabled", havingValue = "true")
    public ApplicationRunner relayRegistrationChecker(RelayProperties props) {
        return args -> {
            String relayAddress = Credentials.create(props.chain().relayPrivateKey()).getAddress();
            Web3j web3j = Web3j.build(new HttpService(props.chain().rpcUrl()));
            try {
                Function function = new Function(
                        "isActiveRelay",
                        List.of(new Address(relayAddress)),
                        List.of(new TypeReference<Bool>() {})
                );
                String encoded = FunctionEncoder.encode(function);
                Transaction call = Transaction.createEthCallTransaction(
                        null, props.chain().settlementAddress(), encoded);
                EthCall response = web3j.ethCall(call, DefaultBlockParameterName.LATEST).send();

                if (response.hasError()) {
                    throw new IllegalStateException(
                            "Relay registration check failed: " + response.getError().getMessage());
                }

                @SuppressWarnings("rawtypes")
                List<Type> result = FunctionReturnDecoder.decode(
                        response.getValue(), function.getOutputParameters());
                boolean active = !result.isEmpty() && ((Bool) result.get(0)).getValue();
                if (!active) {
                    throw new IllegalStateException(
                            "Relay address " + relayAddress + " is not an active relay on settlement " +
                            props.chain().settlementAddress() +
                            ". Approve MIN_RELAY_STAKE tokens and call VayuEpochSettlement.registerRelay() first.");
                }
                LOG.info("Relay registration confirmed: {} is active on {}",
                        relayAddress, props.chain().settlementAddress());
            } finally {
                web3j.shutdown();
            }
        };
    }

    /**
     * Live stake provider: reads VayuEpochSettlement.reporterStake via eth_call.
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
