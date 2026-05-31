import { describe, it, expect, vi, beforeEach } from "vitest";

// ── Ponder virtual-module mocks ───────────────────────────────────────────────
// These modules only exist inside ponder's runtime. Provide stub factories so
// vitest can import api/index.ts without ponder running.

vi.mock("ponder:api", () => ({ db: {} }));
vi.mock("ponder:schema", () => ({ default: {} }));
vi.mock("ponder", () => ({
  // graphql middleware is a no-op in tests — the routes under test don't use it.
  graphql: () => (_c: unknown, next: () => Promise<void>) => next(),
}));

// ── Postgres mock ─────────────────────────────────────────────────────────────
// getRawSql() is lazy — it only calls postgres() on the first request.  The
// mock intercepts that call and returns mockQuery so tests control SQL results.

const mockQuery = vi.hoisted(() => vi.fn().mockResolvedValue([]));
vi.mock("postgres", () => ({ default: vi.fn(() => mockQuery) }));

// ── App import (after mocks are hoisted) ──────────────────────────────────────

// DATABASE_URL must be set before the first route invocation (getRawSql check).
process.env["DATABASE_URL"] = "postgresql://test:test@localhost/testdb";

import app from "../../src/api/index.js";

// ── Fixtures ──────────────────────────────────────────────────────────────────

const REPORTER_ADDR  = "0x1234567890abcdef1234567890abcdef12345678";
const H3_INDEX_STORED = "0x0882830a1fffffff";   // as stored (with 0x prefix)
const H3_INDEX_CLEAN  = "0882830a1fffffff";      // as returned (0x stripped)

const READING_ROW_BY_EPOCH = {
  reporter:  REPORTER_ADDR,
  h3_index:  H3_INDEX_STORED,
  timestamp: 1_700_000_000,
  aqi: 42, pm25: 10, pm10: 20, o3: 5, no2: 3, so2: 1, co: 0,
};

const READING_ROW_BY_REPORTER = {
  epoch_id:  7,
  h3_index:  H3_INDEX_STORED,
  timestamp: 1_700_000_000,
  aqi: 42, pm25: 10, pm10: 20, o3: 5, no2: 3, so2: 1, co: 0,
};

// ── Helpers ───────────────────────────────────────────────────────────────────

beforeEach(() => {
  vi.clearAllMocks();
  mockQuery.mockResolvedValue([]);
});

// ── GET /epochs/:epochId/readings ─────────────────────────────────────────────

describe("GET /epochs/:epochId/readings", () => {
  it("returns 400 for a non-numeric epochId", async () => {
    const res = await app.request("/epochs/abc/readings");
    expect(res.status).toBe(400);
    const body = await res.json() as { error: string };
    expect(body.error).toMatch(/non-negative integer/);
  });

  it("returns 400 for a negative epochId", async () => {
    const res = await app.request("/epochs/-1/readings");
    expect(res.status).toBe(400);
  });

  it("returns an empty readings array when no rows exist", async () => {
    mockQuery.mockResolvedValueOnce([]);
    const res = await app.request("/epochs/7/readings");
    expect(res.status).toBe(200);
    const body = await res.json() as { epochId: number; readings: unknown[] };
    expect(body.epochId).toBe(7);
    expect(body.readings).toHaveLength(0);
  });

  it("returns readings with the 0x prefix stripped from h3Index", async () => {
    mockQuery.mockResolvedValueOnce([READING_ROW_BY_EPOCH]);
    const res = await app.request("/epochs/7/readings");
    expect(res.status).toBe(200);
    const body = await res.json() as { epochId: number; readings: { h3Index: string; reporter: string; aqi: number }[] };
    expect(body.readings).toHaveLength(1);
    expect(body.readings[0].h3Index).toBe(H3_INDEX_CLEAN);
    expect(body.readings[0].reporter).toBe(REPORTER_ADDR);
    expect(body.readings[0].aqi).toBe(42);
  });

  it("passes the epochId to the SQL query", async () => {
    await app.request("/epochs/99/readings");
    const callArgs = JSON.stringify(mockQuery.mock.calls);
    expect(callArgs).toContain("99");
  });
});

// ── GET /reporters/:address/readings ──────────────────────────────────────────

describe("GET /reporters/:address/readings", () => {
  it("returns 400 for a plain-text address", async () => {
    const res = await app.request("/reporters/not-an-address/readings");
    expect(res.status).toBe(400);
    const body = await res.json() as { error: string };
    expect(body.error).toMatch(/20-byte hex/);
  });

  it("returns 400 for an address with the wrong length", async () => {
    const res = await app.request("/reporters/0xdeadbeef/readings");
    expect(res.status).toBe(400);
  });

  it("returns an empty readings array when no rows exist", async () => {
    mockQuery.mockResolvedValueOnce([]);
    const res = await app.request(`/reporters/${REPORTER_ADDR}/readings`);
    expect(res.status).toBe(200);
    const body = await res.json() as { address: string; readings: unknown[] };
    expect(body.address).toBe(REPORTER_ADDR.toLowerCase());
    expect(body.readings).toHaveLength(0);
  });

  it("normalises the address to lowercase in the response", async () => {
    const upper = REPORTER_ADDR.toUpperCase().replace("0X", "0x");
    const res = await app.request(`/reporters/${upper}/readings`);
    const body = await res.json() as { address: string };
    expect(body.address).toBe(REPORTER_ADDR.toLowerCase());
  });

  it("returns readings with the 0x prefix stripped from h3Index", async () => {
    mockQuery.mockResolvedValueOnce([READING_ROW_BY_REPORTER]);
    const res = await app.request(`/reporters/${REPORTER_ADDR}/readings`);
    expect(res.status).toBe(200);
    const body = await res.json() as { readings: { epochId: number; h3Index: string; aqi: number }[] };
    expect(body.readings).toHaveLength(1);
    expect(body.readings[0].epochId).toBe(7);
    expect(body.readings[0].h3Index).toBe(H3_INDEX_CLEAN);
    expect(body.readings[0].aqi).toBe(42);
  });

  it("applies a default limit of 100", async () => {
    await app.request(`/reporters/${REPORTER_ADDR}/readings`);
    // The limit value (100) must appear in the query parameters passed to mockQuery.
    const callArgs = JSON.stringify(mockQuery.mock.calls);
    expect(callArgs).toContain("100");
  });

  it("caps the limit at 500 regardless of the query param", async () => {
    await app.request(`/reporters/${REPORTER_ADDR}/readings?limit=9999`);
    const callArgs = JSON.stringify(mockQuery.mock.calls);
    expect(callArgs).toContain("500");
    expect(callArgs).not.toContain("9999");
  });

  it("accepts a custom limit within bounds", async () => {
    await app.request(`/reporters/${REPORTER_ADDR}/readings?limit=25`);
    const callArgs = JSON.stringify(mockQuery.mock.calls);
    expect(callArgs).toContain("25");
  });
});
