import { z } from "zod";

// ── Mirrors EpochBlobAssembler.java field-for-field ──────────────────────────
// Field order must stay in sync with the relay's assembler output.

const hexRoot = z
  .string()
  .regex(/^0x[0-9a-fA-F]{64}$/, "must be a 0x-prefixed 32-byte hex string")
  .nullable();

const ReporterScoreSchema = z.object({
  reporter: z.string().regex(/^0x[0-9a-fA-F]{40}$/),
  score:    z.number().min(0).max(1),
});

const CellSchema = z.object({
  h3Index:       z.string().regex(/^0x[0-9a-fA-F]{16}$/),
  readingCount:  z.number().int().nonnegative(),
  active:        z.boolean(),
  medianAqi:     z.number().int(),
  avgPm25:       z.number().int(),
  avgPm10:       z.number().int(),
  avgO3:         z.number().int(),
  avgNo2:        z.number().int(),
  avgSo2:        z.number().int(),
  avgCo:         z.number().int(),
  reporterScores: z.array(ReporterScoreSchema),
});

// Optional pollutants are serialised as 0 by the assembler when null.
const ReadingSchema = z.object({
  reporter:  z.string().regex(/^0x[0-9a-fA-F]{40}$/),
  h3Index:   z.string().regex(/^0x[0-9a-fA-F]{16}$/),
  epochId:   z.number().int().nonnegative(),
  timestamp: z.number().int().positive(),
  aqi:       z.number().int().min(1).max(500),
  pm25:      z.number().int().min(1),
  pm10:      z.number().int().min(0),
  o3:        z.number().int().min(0),
  no2:       z.number().int().min(0),
  so2:       z.number().int().min(0),
  co:        z.number().int().min(0),
});

const RewardSchema = z.object({
  reporter:    z.string().regex(/^0x[0-9a-fA-F]{40}$/),
  h3IndexLong: z.number(),
  // Serialised as decimal string by the assembler to avoid float precision loss
  amount:      z.string().regex(/^\d+$/, "amount must be a decimal integer string"),
});

export const EpochBlobSchema = z.object({
  epochId:        z.number().int().nonnegative(),
  totalReadings:  z.number().int().nonnegative(),
  uniqueReporters: z.number().int().nonnegative(),
  activeCells:    z.number().int().nonnegative(),
  dataRoot:       hexRoot,
  rewardRoot:     hexRoot,
  cells:          z.array(CellSchema),
  readings:       z.array(ReadingSchema),
  rewards:        z.array(RewardSchema),
  penaltyList:    z.array(z.string().regex(/^0x[0-9a-fA-F]{40}$/)),
});

export type EpochBlob    = z.infer<typeof EpochBlobSchema>;
export type BlobCell     = z.infer<typeof CellSchema>;
export type BlobReading  = z.infer<typeof ReadingSchema>;
export type BlobReward   = z.infer<typeof RewardSchema>;
