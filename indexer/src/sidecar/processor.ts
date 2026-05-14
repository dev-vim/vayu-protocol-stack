import { sql } from "./db.js";
import { fetchBlob, IpfsFetchError } from "./ipfs.js";
import { EpochBlobSchema, type EpochBlob } from "./blob.schema.js";

/**
 * Processes a single PENDING epoch:
 * 1. Fetch the blob from IPFS
 * 2. Validate it against the schema
 * 3. Insert cell_epochs + readings rows (idempotent)
 * 4. Update the epoch's ipfs_status and total_reward
 *
 * Any failure updates ipfs_status to 'FAILED' so the epoch is not retried.
 */
export async function processEpoch(epochId: number, ipfsCid: string): Promise<void> {
  let rawJson: string;
  try {
    rawJson = await fetchBlob(ipfsCid);
  } catch (err) {
    console.error(`[sidecar] epoch ${epochId}: IPFS fetch failed`, err);
    await markFailed(epochId).catch((e) => console.error(`[sidecar] epoch ${epochId}: markFailed error`, e));
    return;
  }

  let blob: EpochBlob;
  try {
    const parsed = EpochBlobSchema.safeParse(JSON.parse(rawJson));
    if (!parsed.success) {
      console.error(`[sidecar] epoch ${epochId}: blob schema invalid`, parsed.error.flatten());
      await markFailed(epochId).catch((e) => console.error(`[sidecar] epoch ${epochId}: markFailed error`, e));
      return;
    }
    blob = parsed.data;
  } catch (err) {
    console.error(`[sidecar] epoch ${epochId}: blob JSON parse error`, err);
    await markFailed(epochId).catch((e) => console.error(`[sidecar] epoch ${epochId}: markFailed error`, e));
    return;
  }

  // Sanity-check: the blob must describe the epoch we fetched it for.
  if (blob.epochId !== epochId) {
    console.error(`[sidecar] epoch ${epochId}: blob epochId mismatch (blob contains ${blob.epochId})`);
    await markFailed(epochId).catch((e) => console.error(`[sidecar] epoch ${epochId}: markFailed error`, e));
    return;
  }

  try {
    await insertCellEpochs(epochId, blob);
    await insertReadings(epochId, blob);
    await markIngested(epochId, blob);
    console.log(`[sidecar] epoch ${epochId}: ingested — ${blob.readings.length} readings, ${blob.cells.length} cells`);
  } catch (err) {
    console.error(`[sidecar] epoch ${epochId}: DB write failed`, err);
    await markFailed(epochId).catch((e) => console.error(`[sidecar] epoch ${epochId}: markFailed error`, e));
  }
}

async function insertCellEpochs(epochId: number, blob: EpochBlob): Promise<void> {
  if (blob.cells.length === 0) return;

  const rows = blob.cells.map((c) => ({
    epoch_id:       epochId,
    h3_index:       c.h3Index,
    active:         c.active,
    reading_count:  c.readingCount,
    median_aqi:     c.medianAqi,
    avg_pm25:       c.avgPm25,
    avg_pm10:       c.avgPm10,
    avg_o3:         c.avgO3,
    avg_no2:        c.avgNo2,
    avg_so2:        c.avgSo2,
    avg_co:         c.avgCo,
    reporter_scores: JSON.stringify(c.reporterScores),
  }));

  for (const row of rows) {
    await sql`
      INSERT INTO cell_epochs ${sql(row)}
      ON CONFLICT (epoch_id, h3_index) DO NOTHING
    `;
  }
}

async function insertReadings(epochId: number, blob: EpochBlob): Promise<void> {
  if (blob.readings.length === 0) return;

  const rows = blob.readings.map((r) => ({
    epoch_id:  epochId,
    reporter:  r.reporter,
    h3_index:  r.h3Index,
    timestamp: r.timestamp,
    aqi:       r.aqi,
    pm25:      r.pm25,
    pm10:      r.pm10,
    o3:        r.o3,
    no2:       r.no2,
    so2:       r.so2,
    co:        r.co,
  }));

  for (const row of rows) {
    await sql`
      INSERT INTO readings ${sql(row)}
      ON CONFLICT (epoch_id, reporter, h3_index) DO NOTHING
    `;
  }
}

async function markIngested(epochId: number, blob: EpochBlob): Promise<void> {
  // Sum all reward amounts from the blob
  const totalReward = blob.rewards
    .reduce((acc, r) => acc + BigInt(r.amount), 0n)
    .toString();

  await sql`
    UPDATE epochs
    SET    ipfs_status  = 'INGESTED',
           total_reward = ${totalReward}
    WHERE  epoch_id = ${epochId}
  `;
}

async function markFailed(epochId: number): Promise<void> {
  await sql`
    UPDATE epochs
    SET ipfs_status = 'FAILED'
    WHERE epoch_id = ${epochId}
  `;
}
