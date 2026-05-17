import { onchainEnum } from "ponder";

// Maps to VayuTypes.ChallengeType (uint8 on-chain)
// 0=SpatialAnomaly 1=RewardComputation 2=DataIntegrity 3=DuplicateLocation 4=PenaltyListFraud
export const challengeType = onchainEnum("challenge_type", [
  "SPATIAL_ANOMALY",
  "REWARD_COMPUTATION",
  "DATA_INTEGRITY",
  "DUPLICATE_LOCATION",
  "PENALTY_LIST_FRAUD",
]);

// IPFS ingestion lifecycle for each committed epoch
export const ipfsStatus = onchainEnum("ipfs_status", [
  "PENDING",
  "INGESTED",
  "FAILED",
]);
