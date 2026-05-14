import { describe, it, expect } from "vitest";
import { EpochBlobSchema } from "../../src/sidecar/blob.schema.js";

// ── Minimal valid blob used as a base for each test ──────────────────────────

const VALID_BLOB = {
  epochId:         1,
  totalReadings:   2,
  uniqueReporters: 2,
  activeCells:     1,
  dataRoot:        "0x" + "ab".repeat(32),
  rewardRoot:      "0x" + "cd".repeat(32),
  cells: [
    {
      h3Index:        "0x0882830a1fffffff",
      readingCount:   2,
      active:         true,
      medianAqi:      100,
      avgPm25:        30,
      avgPm10:        10,
      avgO3:          5,
      avgNo2:         3,
      avgSo2:         2,
      avgCo:          1,
      reporterScores: [
        { reporter: "0x1111111111111111111111111111111111111111", score: 0.9 },
      ],
    },
  ],
  readings: [
    {
      reporter:  "0x1111111111111111111111111111111111111111",
      h3Index:   "0x0882830a1fffffff",
      epochId:   1,
      timestamp: 1_700_000_000,
      aqi:       100,
      pm25:      30,
      pm10:      10,
      o3:        5,
      no2:       3,
      so2:       2,
      co:        1,
    },
  ],
  rewards: [
    {
      reporter:    "0x1111111111111111111111111111111111111111",
      h3IndexLong: 614894462665760767,
      amount:      "684931506849315068493",
    },
  ],
  penaltyList: [],
};

// ── Happy path ────────────────────────────────────────────────────────────────

describe("EpochBlobSchema — valid blobs", () => {
  it("accepts a fully populated valid blob", () => {
    const result = EpochBlobSchema.safeParse(VALID_BLOB);
    expect(result.success).toBe(true);
  });

  it("accepts null dataRoot and rewardRoot", () => {
    const result = EpochBlobSchema.safeParse({
      ...VALID_BLOB,
      dataRoot:  null,
      rewardRoot: null,
    });
    expect(result.success).toBe(true);
  });

  it("accepts empty cells, readings, rewards, and penaltyList", () => {
    const result = EpochBlobSchema.safeParse({
      ...VALID_BLOB,
      cells: [], readings: [], rewards: [], penaltyList: [],
    });
    expect(result.success).toBe(true);
  });

  it("accepts amount as a large decimal string", () => {
    const result = EpochBlobSchema.safeParse({
      ...VALID_BLOB,
      rewards: [{ ...VALID_BLOB.rewards[0], amount: "99999999999999999999999999" }],
    });
    expect(result.success).toBe(true);
  });
});

// ── Required field failures ───────────────────────────────────────────────────

describe("EpochBlobSchema — missing required fields", () => {
  it.each(["epochId", "totalReadings", "uniqueReporters", "activeCells",
           "cells", "readings", "rewards", "penaltyList"] as const)(
    "rejects blob missing '%s'",
    (field) => {
      const { [field]: _, ...rest } = VALID_BLOB as Record<string, unknown>;
      const result = EpochBlobSchema.safeParse(rest);
      expect(result.success).toBe(false);
    },
  );
});

// ── Root hash format ──────────────────────────────────────────────────────────

describe("EpochBlobSchema — root hash validation", () => {
  it("rejects dataRoot that is not 32 bytes (64 hex chars)", () => {
    const result = EpochBlobSchema.safeParse({
      ...VALID_BLOB,
      dataRoot: "0xdeadbeef",
    });
    expect(result.success).toBe(false);
  });

  it("rejects dataRoot without 0x prefix", () => {
    const result = EpochBlobSchema.safeParse({
      ...VALID_BLOB,
      dataRoot: "ab".repeat(32),
    });
    expect(result.success).toBe(false);
  });
});

// ── Reading validation ────────────────────────────────────────────────────────

describe("EpochBlobSchema — reading validation", () => {
  it("rejects a reading with aqi = 0", () => {
    const result = EpochBlobSchema.safeParse({
      ...VALID_BLOB,
      readings: [{ ...VALID_BLOB.readings[0], aqi: 0 }],
    });
    expect(result.success).toBe(false);
  });

  it("rejects a reading with pm25 = 0", () => {
    const result = EpochBlobSchema.safeParse({
      ...VALID_BLOB,
      readings: [{ ...VALID_BLOB.readings[0], pm25: 0 }],
    });
    expect(result.success).toBe(false);
  });

  it("rejects a reading with negative pm10", () => {
    const result = EpochBlobSchema.safeParse({
      ...VALID_BLOB,
      readings: [{ ...VALID_BLOB.readings[0], pm10: -1 }],
    });
    expect(result.success).toBe(false);
  });

  it("accepts a reading with all optional pollutants set to 0", () => {
    const result = EpochBlobSchema.safeParse({
      ...VALID_BLOB,
      readings: [{ ...VALID_BLOB.readings[0], pm10: 0, o3: 0, no2: 0, so2: 0, co: 0 }],
    });
    expect(result.success).toBe(true);
  });
});

// ── Reward validation ─────────────────────────────────────────────────────────

describe("EpochBlobSchema — reward validation", () => {
  it("rejects amount as a float string", () => {
    const result = EpochBlobSchema.safeParse({
      ...VALID_BLOB,
      rewards: [{ ...VALID_BLOB.rewards[0], amount: "1.5" }],
    });
    expect(result.success).toBe(false);
  });

  it("rejects amount as a plain number", () => {
    const result = EpochBlobSchema.safeParse({
      ...VALID_BLOB,
      rewards: [{ ...VALID_BLOB.rewards[0], amount: 1000 }],
    });
    expect(result.success).toBe(false);
  });
});

// ── Penalty list validation ───────────────────────────────────────────────────

describe("EpochBlobSchema — penalty list validation", () => {
  it("accepts valid hex addresses in penaltyList", () => {
    const result = EpochBlobSchema.safeParse({
      ...VALID_BLOB,
      penaltyList: ["0xDeAdBeEfDeAdBeEfDeAdBeEfDeAdBeEfDeAdBeEf"],
    });
    expect(result.success).toBe(true);
  });

  it("rejects a non-address entry in penaltyList", () => {
    const result = EpochBlobSchema.safeParse({
      ...VALID_BLOB,
      penaltyList: ["not-an-address"],
    });
    expect(result.success).toBe(false);
  });
});
