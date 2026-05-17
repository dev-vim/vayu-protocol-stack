import type { challengeType } from "../schema/enums";

// ── Challenge type mapping ────────────────────────────────────────────────────

// Mirrors VayuTypes.ChallengeType enum order (uint8 on-chain).
// Values must stay in sync with contracts/src/types/VayuTypes.sol.
type ChallengeTypeLabel = typeof challengeType.enumValues[number];

const CHALLENGE_TYPE_LABELS: Record<number, ChallengeTypeLabel> = {
  0: "SPATIAL_ANOMALY",
  1: "REWARD_COMPUTATION",
  2: "DATA_INTEGRITY",
  3: "DUPLICATE_LOCATION",
  4: "PENALTY_LIST_FRAUD",
};

export function toChallengeType(raw: number): ChallengeTypeLabel {
  const label = CHALLENGE_TYPE_LABELS[raw];
  if (label === undefined) {
    throw new Error(`Unknown ChallengeType ordinal: ${raw}`);
  }
  return label;
}

// ── Epoch timing ──────────────────────────────────────────────────────────────

// Must stay in sync with VayuTypes.CHALLENGE_WINDOW (contracts/src/types/VayuTypes.sol).
export const CHALLENGE_WINDOW_SECONDS = 43_200; // 12 hours

export function computeChallengeWindowEnd(committedAt: number): number {
  return committedAt + CHALLENGE_WINDOW_SECONDS;
}

export function isChallengeWindowOpen(committedAt: number, nowSeconds: number): boolean {
  return nowSeconds <= computeChallengeWindowEnd(committedAt);
}
