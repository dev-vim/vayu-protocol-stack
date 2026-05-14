import { fileURLToPath } from "url";
import { sql, runMigrations } from "./db.js";
import { processEpoch } from "./processor.js";

const POLL_INTERVAL_MS = parseInt(process.env.SIDECAR_POLL_INTERVAL_MS ?? "30000", 10);
const BATCH_SIZE       = parseInt(process.env.SIDECAR_BATCH_SIZE       ?? "10",    10);

export async function poll(): Promise<void> {
  let pending: { epoch_id: number; ipfs_cid: string }[];

  try {
    pending = await sql<{ epoch_id: number; ipfs_cid: string }[]>`
      SELECT epoch_id, ipfs_cid
      FROM   epochs
      WHERE  ipfs_status = 'PENDING'
      ORDER  BY epoch_id ASC
      LIMIT  ${BATCH_SIZE}
    `;
  } catch (err: any) {
    // PostgreSQL error 42P01: the epochs table hasn't been created by the
    // indexer yet — skip this cycle and wait for the next interval.
    if (err?.code === "42P01") {
      console.warn("[sidecar] epochs table not yet available, skipping poll");
      return;
    }
    throw err;
  }

  if (pending.length === 0) return;

  console.log(`[sidecar] processing ${pending.length} pending epoch(s)`);

  for (const { epoch_id, ipfs_cid } of pending) {
    try {
      await processEpoch(epoch_id, ipfs_cid);
    } catch (err) {
      // An unexpected error escaped processEpoch (e.g. markFailed itself
      // failed). Log and continue so remaining epochs aren't abandoned.
      console.error(`[sidecar] epoch ${epoch_id}: unhandled error`, err);
    }
  }
}

async function main(): Promise<void> {
  console.log("[sidecar] starting — running migrations");
  await runMigrations();
  console.log("[sidecar] migrations done, beginning poll loop");

  // Run once immediately — a failure here is non-fatal; the interval will retry.
  try {
    await poll();
  } catch (err) {
    console.error("[sidecar] initial poll error", err);
  }

  setInterval(() => {
    poll().catch((err) => console.error("[sidecar] poll error", err));
  }, POLL_INTERVAL_MS);
}

// Guard against auto-running when the module is imported (e.g. in tests).
const __filename = fileURLToPath(import.meta.url);
if (process.argv[1] === __filename) {
  main().catch((err) => {
    console.error("[sidecar] fatal error", err);
    process.exit(1);
  });
}
