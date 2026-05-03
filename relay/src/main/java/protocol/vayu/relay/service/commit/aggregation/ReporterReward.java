package protocol.vayu.relay.service.commit.aggregation;

import java.math.BigInteger;

/**
 * A single reward entry in the REWARD Merkle tree.
 * Corresponds to VayuTypes.rewardLeaf(reporter, epochId, h3Index, amount).
 */
public record ReporterReward(
        String reporter,
        long h3IndexLong,
        BigInteger amount
) {
}
