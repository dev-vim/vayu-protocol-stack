package protocol.vayu.relay.service.commit.aggregation;

import java.util.List;

public record EpochAggregate(
        long epochId,
        int totalReadings,
        int uniqueReporters,
        List<CellAggregate> cells,
        /** Count of cells whose distinct reporter count >= MIN_REPORTERS_PER_CELL (3). */
        int activeCells,
        /** Per-reporter reward allocations; empty when computed by the basic DefaultEpochAggregator. */
        List<ReporterReward> rewards,
        /**
         * 32-byte DATA Merkle root (keccak256 over sorted reading leaves).
         * Null when produced by the basic DefaultEpochAggregator.
         */
        byte[] dataRoot,
        /**
         * 32-byte REWARD Merkle root (keccak256 over sorted reward leaves).
         * Null when produced by the basic DefaultEpochAggregator.
         */
        byte[] rewardRoot,
        /**
         * Reporter addresses eligible for auto-slash (>=10 consecutive zero-score epochs).
         * Empty when produced by the basic DefaultEpochAggregator.
         */
        List<String> penaltyList
) {
}
