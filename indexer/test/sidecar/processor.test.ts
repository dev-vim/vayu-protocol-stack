import { describe, it, expect, vi, beforeEach } from "vitest";

// ── Module mocks (hoisted before imports) ────────────────────────────────────

// mockTx  — the transaction client passed to sql.begin() callbacks.
//   tx`UPDATE ...` → mockTx(["UPDATE ..."], ...values)
//   Default return: { count: 1 } — simulates one row updated (epoch transitions
//   from PENDING to INGESTED).  Override per-test with mockTx.mockResolvedValueOnce().
//
// mockSql — the module-level sql tagged-template tag.
//   sql`INSERT ...` → mockSql(["INSERT ..."], ...values)
//   sql(rows)       → mockSql(rows)
//   sql.begin(fn)   → calls fn(mockTx) directly (no real transaction).
const { mockSql, mockTx } = vi.hoisted(() => {
  const tx = vi.fn();
  const sql = Object.assign(vi.fn().mockReturnValue(undefined), {
    begin: vi.fn((cb: (t: typeof tx) => Promise<unknown>) => cb(tx)),
  });
  return { mockSql: sql, mockTx: tx };
});

vi.mock("../../src/sidecar/db.js", () => ({
  sql: mockSql,
  runMigrations: vi.fn(),
}));

vi.mock("../../src/sidecar/ipfs.js", () => ({
  fetchBlob:      vi.fn(),
  IpfsFetchError: class IpfsFetchError extends Error {
    constructor(public cid: string, message: string) {
      super(message);
      this.name = "IpfsFetchError";
    }
  },
}));

// ── Imports (resolved after mocks are in place) ──────────────────────────────

import { processEpoch } from "../../src/sidecar/processor.js";
import { fetchBlob } from "../../src/sidecar/ipfs.js";

const mockFetchBlob = fetchBlob as unknown as ReturnType<typeof vi.fn>;

// ── Fixtures ──────────────────────────────────────────────────────────────────

const EPOCH_ID = 42;
const IPFS_CID = "QmTestCid";
const REPORTER = "0x1111111111111111111111111111111111111111";
const H3_INDEX = "0x0882830a1fffffff";

const VALID_BLOB = {
  epochId:         EPOCH_ID,
  totalReadings:   2,
  uniqueReporters: 1,
  activeCells:     1,
  dataRoot:        "0x" + "ab".repeat(32),
  rewardRoot:      "0x" + "cd".repeat(32),
  cells: [
    {
      h3Index:        H3_INDEX,
      readingCount:   2,
      active:         true,
      medianAqi:      42,
      avgPm25:        10,
      avgPm10:        20,
      avgO3:          5,
      avgNo2:         3,
      avgSo2:         1,
      avgCo:          0,
      reporterScores: [{ reporter: REPORTER, score: 0.9 }],
    },
  ],
  readings: [
    {
      reporter:  REPORTER,
      h3Index:   H3_INDEX,
      epochId:   EPOCH_ID,
      timestamp: 1_700_000_000,
      aqi:       42,
      pm25:      10,
      pm10:      20,
      o3:        5,
      no2:       3,
      so2:       1,
      co:        0,
    },
  ],
  rewards: [
    {
      reporter:    REPORTER,
      h3IndexLong: 614894462665760767,
      amount:      "1000000000000000000",
    },
  ],
  penaltyList: [],
};

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Serialises all calls made to mockSql (direct sql calls) AND mockTx (calls
 * inside sql.begin() transactions) into one string for assertion.
 */
function allSqlCallsJson(): string {
  return JSON.stringify([...mockSql.mock.calls, ...mockTx.mock.calls]);
}

function expectSqlToHaveIncluded(substring: string): void {
  const all = allSqlCallsJson();
  expect(all, `Expected a SQL call containing "${substring}"`).toContain(substring);
}

function expectSqlNotToHaveIncluded(substring: string): void {
  const all = allSqlCallsJson();
  expect(all, `Expected no SQL call containing "${substring}"`).not.toContain(substring);
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe("processEpoch()", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Reset mockTx fully (clears the once-queue) then set the default: one row
    // updated, meaning the epoch transitioned PENDING → INGESTED successfully.
    mockTx.mockReset().mockResolvedValue({ count: 1 });
  });

  it("marks epoch FAILED when fetchBlob throws", async () => {
    mockFetchBlob.mockRejectedValueOnce(new Error("gateway timeout"));
    await processEpoch(EPOCH_ID, IPFS_CID);
    expectSqlToHaveIncluded("FAILED");
  });

  it("marks epoch FAILED when the blob is not valid JSON", async () => {
    mockFetchBlob.mockResolvedValueOnce("not-json{{{{");
    await processEpoch(EPOCH_ID, IPFS_CID);
    expectSqlToHaveIncluded("FAILED");
  });

  it("marks epoch FAILED when the blob fails schema validation", async () => {
    const invalid = { ...VALID_BLOB, epochId: "not-a-number" };
    mockFetchBlob.mockResolvedValueOnce(JSON.stringify(invalid));
    await processEpoch(EPOCH_ID, IPFS_CID);
    expectSqlToHaveIncluded("FAILED");
  });

  it("marks epoch FAILED when blob.epochId does not match the requested epochId", async () => {
    const mismatched = { ...VALID_BLOB, epochId: EPOCH_ID + 1 };
    mockFetchBlob.mockResolvedValueOnce(JSON.stringify(mismatched));
    await processEpoch(EPOCH_ID, IPFS_CID);
    expectSqlToHaveIncluded("FAILED");
  });

  it("writes cell_epochs, readings, and marks INGESTED on a valid blob", async () => {
    mockFetchBlob.mockResolvedValueOnce(JSON.stringify(VALID_BLOB));
    await processEpoch(EPOCH_ID, IPFS_CID);
    expectSqlToHaveIncluded("cell_epochs");
    expectSqlToHaveIncluded("readings");
    expectSqlToHaveIncluded("INGESTED");
    expectSqlNotToHaveIncluded("FAILED");
  });

  it("marks INGESTED and skips inserts when cells and readings are empty", async () => {
    const emptyBlob = { ...VALID_BLOB, cells: [], readings: [], rewards: [] };
    mockFetchBlob.mockResolvedValueOnce(JSON.stringify(emptyBlob));
    await processEpoch(EPOCH_ID, IPFS_CID);
    expectSqlNotToHaveIncluded("cell_epochs");
    expectSqlNotToHaveIncluded("readings");
    expectSqlToHaveIncluded("INGESTED");
  });

  it("marks epoch FAILED when a DB write throws during ingestion", async () => {
    mockFetchBlob.mockResolvedValueOnce(JSON.stringify(VALID_BLOB));
    // sql(row) is called first (interpolated value), then the outer INSERT template.
    // We reject the outer INSERT template call (2nd) so the await inside
    // insertCellEpochs throws and propagates to processEpoch's catch block.
    mockSql
      .mockReturnValueOnce(undefined)                                      // sql(row)
      .mockReturnValueOnce(Promise.reject(new Error("deadlock")))          // INSERT template
      .mockReturnValue(undefined);                                         // markFailed UPDATE
    await processEpoch(EPOCH_ID, IPFS_CID);
    expectSqlToHaveIncluded("FAILED");
  });

  it("does not throw when both fetchBlob and markFailed fail", async () => {
    mockFetchBlob.mockRejectedValueOnce(new Error("gateway down"));
    mockSql.mockReturnValueOnce(Promise.reject(new Error("db also down")));
    await expect(processEpoch(EPOCH_ID, IPFS_CID)).resolves.toBeUndefined();
  });

  // ── Reporter aggregate updates ─────────────────────────────────────────────

  it("updates totalReadings and totalRewards for each reporter after ingestion", async () => {
    mockFetchBlob.mockResolvedValueOnce(JSON.stringify(VALID_BLOB));
    await processEpoch(EPOCH_ID, IPFS_CID);

    // The transaction must have updated the reporters row.
    expectSqlToHaveIncluded("total_readings");
    expectSqlToHaveIncluded("total_rewards");
    // The reporter address and reward amount from the fixture must appear.
    expectSqlToHaveIncluded(REPORTER.toLowerCase());
    expectSqlToHaveIncluded("1000000000000000000");
  });

  it("skips reporter aggregate updates when epoch was already INGESTED (idempotency)", async () => {
    mockFetchBlob.mockResolvedValueOnce(JSON.stringify(VALID_BLOB));
    // Simulate the epoch being already INGESTED — the guarded UPDATE returns 0 rows.
    mockTx.mockResolvedValueOnce({ count: 0 });
    await processEpoch(EPOCH_ID, IPFS_CID);

    // The epoch status transition was attempted (INGESTED appears in the tx call)
    // but reporter aggregate columns must NOT have been touched.
    expectSqlNotToHaveIncluded("total_readings");
    expectSqlNotToHaveIncluded("total_rewards");
  });

  it("skips reporter updates when the blob has no readings or rewards", async () => {
    const emptyRewardsBlob = { ...VALID_BLOB, readings: [], rewards: [] };
    mockFetchBlob.mockResolvedValueOnce(JSON.stringify(emptyRewardsBlob));
    await processEpoch(EPOCH_ID, IPFS_CID);
    expectSqlToHaveIncluded("INGESTED");
    expectSqlNotToHaveIncluded("total_readings");
    expectSqlNotToHaveIncluded("total_rewards");
  });
});
