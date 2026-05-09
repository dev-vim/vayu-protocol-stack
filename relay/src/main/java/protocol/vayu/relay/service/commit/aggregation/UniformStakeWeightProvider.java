package protocol.vayu.relay.service.commit.aggregation;

import java.math.BigInteger;

/**
 * Default StakeWeightProvider that assigns equal weight (1) to every reporter.
 * Replace with an on-chain querying implementation once the settlement contract
 * address is wired into the relay configuration.
 */
public class UniformStakeWeightProvider implements StakeWeightProvider {

    @Override
    public BigInteger stakeOf(String reporter) {
        return BigInteger.ONE;
    }
}
