package protocol.vayu.relay.service.ingestion.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import protocol.vayu.relay.service.commit.aggregation.StakeWeightProvider;

import java.math.BigInteger;

/**
 * ReporterStakeChecker backed by the configured {@link StakeWeightProvider}.
 * Active when {@code relay.security.stake-check-enabled=true}.
 *
 * <p>A reporter is considered eligible if their active stake in
 * {@code VayuEpochSettlement} is greater than zero.
 */
@Component
@ConditionalOnProperty(
        prefix = "relay.security",
        name = "stake-check-enabled",
        havingValue = "true"
)
public class StakeWeightReporterStakeChecker implements ReporterStakeChecker {

    private final StakeWeightProvider stakeWeightProvider;

    public StakeWeightReporterStakeChecker(StakeWeightProvider stakeWeightProvider) {
        this.stakeWeightProvider = stakeWeightProvider;
    }

    @Override
    public boolean hasActiveStake(String reporter) {
        return stakeWeightProvider.stakeOf(reporter).compareTo(BigInteger.ZERO) > 0;
    }
}
