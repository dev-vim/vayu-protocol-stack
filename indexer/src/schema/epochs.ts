import { index, onchainTable } from "ponder";
import { ipfsStatus } from "./enums";

// Primary sync object. One row per EpochCommitted event.
// IPFS-sourced columns (totalReward) are populated by the async IPFS worker.
export const epochs = onchainTable(
  "epochs",
  (t) => ({
    epochId:            t.integer().primaryKey(),
    relay:              t.hex().notNull(),
    dataRoot:           t.hex().notNull(),
    rewardRoot:         t.hex().notNull(),
    ipfsCid:            t.text().notNull(),
    activeCells:        t.integer().notNull(),
    totalReadings:      t.integer().notNull(),
    totalReward:        t.bigint().default(0n),    // populated after IPFS ingestion
    committedAt:        t.integer().notNull(),      // block.timestamp (UNIX seconds)
    blockNumber:        t.bigint().notNull(),
    txHash:             t.hex().notNull(),
    challengeWindowEnd: t.integer().notNull(),      // committedAt + 43200 (12h)
    finalized:          t.boolean().notNull().default(false),
    swept:              t.boolean().notNull().default(false),
    ipfsStatus:         ipfsStatus("ipfs_status").notNull().default("PENDING"),
  }),
  (table) => ({
    relayIdx:       index().on(table.relay),
    committedAtIdx: index().on(table.committedAt),
  })
);
