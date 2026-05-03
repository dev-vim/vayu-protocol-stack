package protocol.vayu.relay.service.commit.aggregation;

import java.math.BigInteger;

/**
 * Provides the staked token weight for a reporter address.
 * Used by ProtocolEpochAggregator to weight rewards by stake.
 */
public interface StakeWeightProvider {

    /**
     * @param reporter lowercase 0x-prefixed Ethereum address
     * @return the reporter's active stake in token wei; never null, never negative
     */
    BigInteger stakeOf(String reporter);
}
