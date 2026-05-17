import { index, onchainTable } from "ponder";

// One row per reporter address.
// Built from Staked / UnstakeInitiated / Withdrawn events.
// totalReadings and totalRewards are updated by the IPFS worker after ingestion.
export const reporters = onchainTable(
  "reporters",
  (t) => ({
    address:        t.hex().primaryKey(),
    staker:         t.hex(),                           // who called stakeFor()
    stake:          t.bigint().notNull().default(0n),
    pendingUnstake: t.bigint().notNull().default(0n),
    withdrawableAt: t.integer(),
    totalReadings:  t.integer().notNull().default(0),
    totalRewards:   t.bigint().notNull().default(0n),
    totalClaimed:   t.bigint().notNull().default(0n),
    totalSlashed:   t.bigint().notNull().default(0n),
    isSlashed:      t.boolean().notNull().default(false),
    firstSeenEpoch: t.integer(),
    lastSeenEpoch:  t.integer(),
  }),
  (table) => ({
    stakeIdx:    index().on(table.stake),
    lastSeenIdx: index().on(table.lastSeenEpoch),
  })
);
