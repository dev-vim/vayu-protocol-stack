import { index, onchainTable, primaryKey } from "ponder";

// One row per RewardClaimed event.
export const claims = onchainTable(
  "claims",
  (t) => ({
    epochId:     t.integer().notNull(),
    reporter:    t.hex().notNull(),
    cellId:      t.bigint().notNull(),    // H3 index (uint64)
    amount:      t.bigint().notNull(),
    blockNumber: t.bigint().notNull(),
    txHash:      t.hex().notNull(),
    claimedAt:   t.integer().notNull(),   // block.timestamp
  }),
  (table) => ({
    pk:          primaryKey({ columns: [table.epochId, table.reporter, table.cellId] }),
    reporterIdx: index().on(table.reporter),
    epochIdx:    index().on(table.epochId),
  })
);
