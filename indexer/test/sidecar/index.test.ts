import { describe, it, expect, vi, beforeEach } from "vitest";

// ── Module mocks (hoisted before imports) ────────────────────────────────────

vi.mock("../../src/sidecar/db.js", () => ({
  sql:            vi.fn(),
  runMigrations:  vi.fn(),
}));

vi.mock("../../src/sidecar/processor.js", () => ({
  processEpoch: vi.fn(),
}));

// ── Imports (resolved after mocks are in place) ──────────────────────────────

import { sql } from "../../src/sidecar/db.js";
import { processEpoch } from "../../src/sidecar/processor.js";
import { poll } from "../../src/sidecar/index.js";

// Typed shorthands for the mocked functions
const mockSql         = sql         as unknown as ReturnType<typeof vi.fn>;
const mockProcessEpoch = processEpoch as unknown as ReturnType<typeof vi.fn>;

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Build a PostgreSQL error with the given code (simulates pg driver errors). */
function pgError(code: string, message = "pg error"): Error {
  return Object.assign(new Error(message), { code });
}

type PendingRow = { epoch_id: number; ipfs_cid: string };

// ── Tests ─────────────────────────────────────────────────────────────────────

describe("poll()", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ── Missing table ──────────────────────────────────────────────────────────

  it("returns without processing when the epochs table does not exist yet (42P01)", async () => {
    mockSql.mockRejectedValueOnce(pgError("42P01", 'relation "epochs" does not exist'));

    await expect(poll()).resolves.toBeUndefined();
    expect(mockProcessEpoch).not.toHaveBeenCalled();
  });

  it("re-throws unexpected DB errors from the SELECT", async () => {
    mockSql.mockRejectedValueOnce(pgError("08006", "connection failure"));

    await expect(poll()).rejects.toThrow("connection failure");
    expect(mockProcessEpoch).not.toHaveBeenCalled();
  });

  // ── Empty result set ───────────────────────────────────────────────────────

  it("returns without processing when no pending epochs exist", async () => {
    mockSql.mockResolvedValueOnce([] as PendingRow[]);

    await expect(poll()).resolves.toBeUndefined();
    expect(mockProcessEpoch).not.toHaveBeenCalled();
  });

  // ── Normal processing path ─────────────────────────────────────────────────

  it("calls processEpoch for each pending row", async () => {
    const rows: PendingRow[] = [
      { epoch_id: 1, ipfs_cid: "QmAbc" },
      { epoch_id: 2, ipfs_cid: "QmDef" },
    ];
    mockSql.mockResolvedValueOnce(rows);
    mockProcessEpoch.mockResolvedValue(undefined);

    await poll();

    expect(mockProcessEpoch).toHaveBeenCalledTimes(2);
    expect(mockProcessEpoch).toHaveBeenCalledWith(1, "QmAbc");
    expect(mockProcessEpoch).toHaveBeenCalledWith(2, "QmDef");
  });

  it("processes remaining epochs when one throws an unexpected error", async () => {
    const rows: PendingRow[] = [
      { epoch_id: 10, ipfs_cid: "QmFail" },
      { epoch_id: 11, ipfs_cid: "QmOk"   },
    ];
    mockSql.mockResolvedValueOnce(rows);
    mockProcessEpoch
      .mockRejectedValueOnce(new Error("unexpected db failure"))
      .mockResolvedValueOnce(undefined);

    await expect(poll()).resolves.toBeUndefined();
    expect(mockProcessEpoch).toHaveBeenCalledTimes(2);
  });
});
