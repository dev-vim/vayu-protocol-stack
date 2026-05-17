import { onchainTable } from "ponder";

// One row per relay address.
// Built from RelayRegistered and RelayDeactivated events.
export const relays = onchainTable("relays", (t) => ({
  address:         t.hex().primaryKey(),
  stake:           t.bigint().notNull().default(0n),
  pendingUnstake:  t.bigint().notNull().default(0n),
  withdrawableAt:  t.integer(),
  isActive:        t.boolean().notNull().default(true),
  epochsCommitted: t.integer().notNull().default(0),
  totalSlashed:    t.bigint().notNull().default(0n),
  registeredAt:    t.integer(),
  registeredBlock: t.bigint(),
  registeredTx:    t.hex(),
}));
