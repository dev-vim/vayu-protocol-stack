import { describe, expect, it } from "vitest";
import {
  CHALLENGE_WINDOW_SECONDS,
  computeChallengeWindowEnd,
  isChallengeWindowOpen,
  toChallengeType,
} from "../../src/lib/epochs";

describe("toChallengeType", () => {
  it("maps each known ordinal to the correct label", () => {
    expect(toChallengeType(0)).toBe("SPATIAL_ANOMALY");
    expect(toChallengeType(1)).toBe("REWARD_COMPUTATION");
    expect(toChallengeType(2)).toBe("DATA_INTEGRITY");
    expect(toChallengeType(3)).toBe("DUPLICATE_LOCATION");
    expect(toChallengeType(4)).toBe("PENALTY_LIST_FRAUD");
  });

  it("throws on an unknown ordinal", () => {
    expect(() => toChallengeType(5)).toThrow("Unknown ChallengeType ordinal: 5");
    expect(() => toChallengeType(-1)).toThrow("Unknown ChallengeType ordinal: -1");
  });
});

describe("computeChallengeWindowEnd", () => {
  it("adds CHALLENGE_WINDOW_SECONDS to committedAt", () => {
    const committedAt = 1_000_000;
    expect(computeChallengeWindowEnd(committedAt)).toBe(committedAt + CHALLENGE_WINDOW_SECONDS);
  });

  it("equals committedAt + 43200 (12 hours)", () => {
    const committedAt = 1_715_000_000;
    expect(computeChallengeWindowEnd(committedAt)).toBe(1_715_043_200);
  });

  it("handles zero committedAt", () => {
    expect(computeChallengeWindowEnd(0)).toBe(CHALLENGE_WINDOW_SECONDS);
  });
});

describe("isChallengeWindowOpen", () => {
  const committedAt = 1_000_000;
  const windowEnd = committedAt + CHALLENGE_WINDOW_SECONDS;

  it("returns true when now is before the window end", () => {
    expect(isChallengeWindowOpen(committedAt, windowEnd - 1)).toBe(true);
  });

  it("returns true at exactly the window end boundary", () => {
    expect(isChallengeWindowOpen(committedAt, windowEnd)).toBe(true);
  });

  it("returns false when now is after the window end", () => {
    expect(isChallengeWindowOpen(committedAt, windowEnd + 1)).toBe(false);
  });

  it("returns true immediately after commit", () => {
    expect(isChallengeWindowOpen(committedAt, committedAt)).toBe(true);
  });
});
