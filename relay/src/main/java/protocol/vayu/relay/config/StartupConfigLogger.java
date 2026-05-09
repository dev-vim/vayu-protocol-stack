package protocol.vayu.relay.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class StartupConfigLogger implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(StartupConfigLogger.class);

    private final RelayProperties relayProperties;
    private final Environment environment;

    public StartupConfigLogger(RelayProperties relayProperties, Environment environment) {
        this.relayProperties = relayProperties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String[] activeProfiles = environment.getActiveProfiles();
        String profiles = activeProfiles.length == 0 ? "(default)" : String.join(",", activeProfiles);

        RelayProperties.Epoch epoch = relayProperties.epoch();
        RelayProperties.Validation validation = relayProperties.validation();
        RelayProperties.Security security = relayProperties.security();
        RelayProperties.Eip712 eip712 = security.eip712();
        RelayProperties.Ipfs ipfs = relayProperties.ipfs();
        RelayProperties.Chain chain = relayProperties.chain();

        LOG.info(
                "Loaded application configuration:\n" +
                "  spring.application.name={}\n" +
                "  server.port={}\n" +
                "  activeProfiles={}\n" +
                "  relay.epoch.duration-seconds={}\n" +
                "  relay.epoch.commit-check-interval-ms={}\n" +
                "  relay.epoch.timestamp-tolerance-seconds={}\n" +
                "  relay.epoch.min-reporters-per-cell={}\n" +
                "  relay.epoch.scoring-tolerance-aqi={}\n" +
                "  relay.epoch.epoch-budget-wei={}\n" +
                "  relay.validation.required-h3-resolution={}\n" +
                "  relay.validation.rate-limit-window-seconds={}\n" +
                "  relay.validation.min-aqi={}\n" +
                "  relay.validation.min-pm25={}\n" +
                "  relay.security.signature-verification-enabled={}\n" +
                "  relay.security.stake-check-enabled={}\n" +
                "  relay.security.eip712.domain-name={}\n" +
                "  relay.security.eip712.domain-version={}\n" +
                "  relay.security.eip712.chain-id={}\n" +
                "  relay.security.eip712.verifying-contract={}\n" +
                "  relay.ipfs.provider={}\n" +
                "  relay.ipfs.kubo-api-url={}\n" +
                "  relay.ipfs.pinata-jwt={}\n" +
                "  relay.chain.rpc-url={}\n" +
                "  relay.chain.settlement-address={}\n" +
                "  relay.chain.on-chain-commit-enabled={}\n" +
                "  relay.chain.relay-private-key={}\n" +
                "  relay.chain.chain-id={}",
                environment.getProperty("spring.application.name", "(unset)"),
                environment.getProperty("server.port", "(unset)"),
                profiles,
                epoch.durationSeconds(),
                epoch.commitCheckIntervalMs(),
                epoch.timestampToleranceSeconds(),
                epoch.minReportersPerCell(),
                epoch.scoringToleranceAqi(),
                epoch.epochBudgetWei(),
                validation.requiredH3Resolution(),
                validation.rateLimitWindowSeconds(),
                validation.minAqi(),
                validation.minPm25(),
                security.signatureVerificationEnabled(),
                security.stakeCheckEnabled(),
                eip712.domainName(),
                eip712.domainVersion(),
                eip712.chainId(),
                eip712.verifyingContract(),
                ipfs.provider(),
                ipfs.kuboApiUrl(),
                maskSecret(ipfs.pinataJwt()),
                chain.rpcUrl(),
                chain.settlementAddress(),
                chain.onChainCommitEnabled(),
                maskSecret(chain.relayPrivateKey()),
                chain.chainId()
        );
    }

    private static String maskSecret(String value) {
        if (value == null || value.isBlank()) {
            return "(empty)";
        }
        int visible = Math.min(4, value.length());
        return "***" + value.substring(value.length() - visible);
    }
}