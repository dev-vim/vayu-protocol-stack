import { db } from "ponder:api";
import schema from "ponder:schema";
import { graphql } from "ponder";
import { Hono } from "hono";
import postgres from "postgres";

// Lazy raw-SQL pool for querying sidecar-managed tables (cell_epochs, readings).
// The main Ponder `db` only knows about onchainTable/offchainTable definitions.
let _rawSql: ReturnType<typeof postgres> | null = null;
function getRawSql(): ReturnType<typeof postgres> {
  if (!_rawSql) {
    const url = process.env.DATABASE_URL;
    if (!url) throw new Error("DATABASE_URL is not set");
    _rawSql = postgres(url, { max: 3 });
  }
  return _rawSql;
}

const app = new Hono();

app.use("/graphql", graphql({ db, schema }));

app.get("/hello", (c) => {
  return c.text("Hello, world!");
});

// ── Per-cell H3 data for a given epoch ──────────────────────────────────────
// Returns the active cells ingested from the epoch IPFS blob, ready for
// deck.gl H3HexagonLayer rendering in the dashboard.
app.get("/epochs/:epochId/cells", async (c) => {
  const raw = c.req.param("epochId");
  const epochId = parseInt(raw, 10);
  if (isNaN(epochId) || epochId < 0) {
    return c.json({ error: "epochId must be a non-negative integer" }, 400);
  }

  const sql = getRawSql();

  type CellRow = {
    h3_index: string;
    median_aqi: number;
    reading_count: number;
    avg_pm25: number;
    avg_pm10: number;
    avg_o3: number;
    avg_no2: number;
    avg_so2: number;
    avg_co: number;
  };

  const rows = await sql<CellRow[]>`
    SELECT  h3_index,
            median_aqi,
            reading_count,
            avg_pm25,
            avg_pm10,
            avg_o3,
            avg_no2,
            avg_so2,
            avg_co
    FROM    cell_epochs
    WHERE   epoch_id = ${epochId}
      AND   active   = TRUE
    ORDER BY median_aqi DESC
  `;

  return c.json({
    epochId,
    cells: rows.map((r) => ({
      // h3_index is stored with a 0x prefix — strip it so h3-js / deck.gl
      // receive the standard string form ("8a2a100d2dfffff")
      h3Index:      r.h3_index.replace(/^0x/, ""),
      medianAqi:    r.median_aqi,
      readingCount: r.reading_count,
      avgPm25:      r.avg_pm25,
      avgPm10:      r.avg_pm10,
      avgO3:        r.avg_o3,
      avgNo2:       r.avg_no2,
      avgSo2:       r.avg_so2,
      avgCo:        r.avg_co,
    })),
  });
});

// ── Per-epoch raw readings ─────────────────────────────────────────────────
// Returns all readings ingested from the epoch IPFS blob.
app.get("/epochs/:epochId/readings", async (c) => {
  const raw = c.req.param("epochId");
  const epochId = parseInt(raw, 10);
  if (isNaN(epochId) || epochId < 0) {
    return c.json({ error: "epochId must be a non-negative integer" }, 400);
  }

  const sql = getRawSql();

  type ReadingRow = {
    reporter: string;
    h3_index: string;
    timestamp: number;
    aqi: number;
    pm25: number;
    pm10: number;
    o3: number;
    no2: number;
    so2: number;
    co: number;
  };

  const rows = await sql<ReadingRow[]>`
    SELECT reporter, h3_index, timestamp, aqi, pm25, pm10, o3, no2, so2, co
    FROM   readings
    WHERE  epoch_id = ${epochId}
    ORDER BY reporter, h3_index
  `;

  return c.json({
    epochId,
    readings: rows.map((r) => ({
      reporter:  r.reporter,
      h3Index:   r.h3_index.replace(/^0x/, ""),
      timestamp: r.timestamp,
      aqi:       r.aqi,
      pm25:      r.pm25,
      pm10:      r.pm10,
      o3:        r.o3,
      no2:       r.no2,
      so2:       r.so2,
      co:        r.co,
    })),
  });
});

// ── Per-reporter raw readings ──────────────────────────────────────────────
// Returns all readings submitted by a reporter across all indexed epochs.
// Optional query param: limit (default 100, max 500).
app.get("/reporters/:address/readings", async (c) => {
  const raw = c.req.param("address").toLowerCase();
  if (!/^0x[0-9a-f]{40}$/.test(raw)) {
    return c.json({ error: "address must be a 0x-prefixed 20-byte hex string" }, 400);
  }

  const limitParam = c.req.query("limit");
  const limit = Math.min(Math.max(1, parseInt(limitParam ?? "100", 10) || 100), 500);

  const sql = getRawSql();

  type ReadingRow = {
    epoch_id: number;
    h3_index: string;
    timestamp: number;
    aqi: number;
    pm25: number;
    pm10: number;
    o3: number;
    no2: number;
    so2: number;
    co: number;
  };

  const rows = await sql<ReadingRow[]>`
    SELECT epoch_id, h3_index, timestamp, aqi, pm25, pm10, o3, no2, so2, co
    FROM   readings
    WHERE  LOWER(reporter) = ${raw}
    ORDER BY epoch_id DESC, h3_index
    LIMIT  ${limit}
  `;

  return c.json({
    address: raw,
    readings: rows.map((r) => ({
      epochId:   r.epoch_id,
      h3Index:   r.h3_index.replace(/^0x/, ""),
      timestamp: r.timestamp,
      aqi:       r.aqi,
      pm25:      r.pm25,
      pm10:      r.pm10,
      o3:        r.o3,
      no2:       r.no2,
      so2:       r.so2,
      co:        r.co,
    })),
  });
});

export default app;