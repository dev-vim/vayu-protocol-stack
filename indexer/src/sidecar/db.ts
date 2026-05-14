import postgres from "postgres";

const DATABASE_URL = process.env.DATABASE_URL;
if (!DATABASE_URL) throw new Error("DATABASE_URL is not set");

// Single shared connection pool for the sidecar process.
export const sql = postgres(DATABASE_URL, { max: 5 });

/**
 * Creates the sidecar-owned tables if they don't exist.
 * Safe to call on every startup (idempotent).
 */
export async function runMigrations(): Promise<void> {
  // Ponder installs a live_query() trigger on every table it manages (including
  // `epochs`). That trigger INSERTs into live_query_tables, which Ponder
  // normally creates at startup. If the sidecar runs before (or without) the
  // indexer, any UPDATE to `epochs` will fire the trigger and fail with
  // 42P01. Creating the table here satisfies the trigger's dependency.
  await sql`
    CREATE TABLE IF NOT EXISTS live_query_tables (
      table_name TEXT PRIMARY KEY
    )
  `;

  await sql`
    CREATE TABLE IF NOT EXISTS cell_epochs (
      epoch_id       INTEGER     NOT NULL,
      h3_index       TEXT        NOT NULL,
      active         BOOLEAN     NOT NULL,
      reading_count  INTEGER     NOT NULL,
      median_aqi     INTEGER     NOT NULL,
      avg_pm25       INTEGER     NOT NULL,
      avg_pm10       INTEGER     NOT NULL,
      avg_o3         INTEGER     NOT NULL,
      avg_no2        INTEGER     NOT NULL,
      avg_so2        INTEGER     NOT NULL,
      avg_co         INTEGER     NOT NULL,
      reporter_scores JSONB      NOT NULL DEFAULT '[]',
      PRIMARY KEY (epoch_id, h3_index)
    )
  `;

  await sql`
    CREATE TABLE IF NOT EXISTS readings (
      epoch_id   INTEGER  NOT NULL,
      reporter   TEXT     NOT NULL,
      h3_index   TEXT     NOT NULL,
      timestamp  BIGINT   NOT NULL,
      aqi        INTEGER  NOT NULL,
      pm25       INTEGER  NOT NULL,
      pm10       INTEGER  NOT NULL,
      o3         INTEGER  NOT NULL,
      no2        INTEGER  NOT NULL,
      so2        INTEGER  NOT NULL,
      co         INTEGER  NOT NULL,
      PRIMARY KEY (epoch_id, reporter, h3_index)
    )
  `;

  await sql`
    CREATE INDEX IF NOT EXISTS readings_epoch_id_idx ON readings (epoch_id)
  `;

  await sql`
    CREATE INDEX IF NOT EXISTS readings_reporter_idx ON readings (reporter)
  `;
}
