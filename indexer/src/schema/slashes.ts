import { index, onchainTable, primaryKey } from "ponder";
import { challengeType } from "./enums";

// One row per Slashed event.
// A single challenge can produce multiple slashes (e.g. SpatialAnomaly slashes
// every reporter in the disputed cell). This table captures each one individually
// and links back to the parent challenge via (epochId, challengeType).
export const slashes = onchainTable(
  "slashes",
  (t) => ({
    epochId:         t.integer().notNull(),
    challengeType:   challengeType("challenge_type").notNull(),
    offender:        t.hex().notNull(),       // reporter or relay address
    slashAmount:     t.bigint().notNull(),
    fishermanReward: t.bigint().notNull(),
    blockNumber:     t.bigint().notNull(),
    txHash:          t.hex().notNull(),
  }),
  (table) => ({
    pk:           primaryKey({ columns: [table.epochId, table.challengeType, table.offender] }),
    offenderIdx:  index().on(table.offender),
    epochIdx:     index().on(table.epochId),
  })
);
