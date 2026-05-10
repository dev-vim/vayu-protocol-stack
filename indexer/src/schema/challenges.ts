import { index, onchainTable, primaryKey } from "ponder";
import { challengeType } from "./enums";

// One row per ChallengeSubmitted event.
// Updated in-place when ChallengeResolved and Slashed events arrive.
export const challenges = onchainTable(
  "challenges",
  (t) => ({
    epochId:       t.integer().notNull(),
    challenger:    t.hex().notNull(),
    challengeType: challengeType("challenge_type").notNull(),
    succeeded:     t.boolean(),           // null until ChallengeResolved
    target:        t.hex(),               // slashed address; null if challenge failed
    slashAmount:   t.bigint().default(0n),
    blockNumber:   t.bigint().notNull(),
    txHash:        t.hex().notNull(),
  }),
  (table) => ({
    pk:            primaryKey({ columns: [table.epochId, table.challenger, table.challengeType] }),
    challengerIdx: index().on(table.challenger),
    epochIdx:      index().on(table.epochId),
  })
);
