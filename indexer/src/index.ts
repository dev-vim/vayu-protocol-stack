import { ponder } from "ponder:registry";
import {
  challenges,
  claims,
  epochs,
  relays,
  reporters,
  slashes,
} from "ponder:schema";
import { computeChallengeWindowEnd, toChallengeType } from "./lib/epochs";

// ── Epoch lifecycle ─────────────────────────────────────────────────────────

ponder.on("VayuEpochSettlement:EpochCommitted", async ({ event, context }) => {
  const { epochId, relay, dataRoot, rewardRoot, ipfsCid, activeCells, totalReadings } =
    event.args;

  await context.db
    .insert(epochs)
    .values({
      epochId:            Number(epochId),
      relay,
      dataRoot,
      rewardRoot,
      ipfsCid,
      activeCells:        Number(activeCells),
      totalReadings:      Number(totalReadings),
      committedAt:        Number(event.block.timestamp),
      blockNumber:        event.block.number,
      txHash:             event.transaction.hash,
      challengeWindowEnd: computeChallengeWindowEnd(Number(event.block.timestamp)),
      ipfsStatus:         "PENDING",
    })
    .onConflictDoNothing();

  // Increment relay epoch counter
  await context.db
    .update(relays, { address: relay })
    .set((row) => ({ epochsCommitted: row.epochsCommitted + 1 }));
});

ponder.on("VayuEpochSettlement:EpochSwept", async ({ event, context }) => {
  const { epochId } = event.args;

  await context.db
    .update(epochs, { epochId: Number(epochId) })
    .set({ swept: true });
});

// ── Reward claims ────────────────────────────────────────────────────────────

ponder.on("VayuEpochSettlement:RewardClaimed", async ({ event, context }) => {
  const { epochId, reporter, h3Index, amount } = event.args;

  await context.db
    .insert(claims)
    .values({
      epochId:     Number(epochId),
      reporter,
      cellId:      h3Index,
      amount,
      blockNumber: event.block.number,
      txHash:      event.transaction.hash,
      claimedAt:   Number(event.block.timestamp),
    })
    .onConflictDoNothing();

  // Update reporter totals
  await context.db
    .update(reporters, { address: reporter })
    .set((row) => ({ totalClaimed: row.totalClaimed + amount }));
});

// ── Challenges & slashing ────────────────────────────────────────────────────

ponder.on("VayuEpochSettlement:ChallengeSubmitted", async ({ event, context }) => {
  const { epochId, challenger, challengeType } = event.args;

  await context.db
    .insert(challenges)
    .values({
      epochId:       Number(epochId),
      challenger,
      challengeType: toChallengeType(challengeType),
      blockNumber:   event.block.number,
      txHash:        event.transaction.hash,
    })
    .onConflictDoNothing();
});

ponder.on("VayuEpochSettlement:ChallengeResolved", async ({ event, context }) => {
  const { epochId, challenger, challengeType, succeeded } = event.args;

  await context.db
    .update(challenges, {
      epochId:       Number(epochId),
      challenger,
      challengeType: toChallengeType(challengeType),
    })
    .set({ succeeded });
});

ponder.on("VayuEpochSettlement:Slashed", async ({ event, context }) => {
  const { offender, slashAmount, fishermanReward, challengeType, epochId } = event.args;

  // One row per Slashed event. SpatialAnomaly emits one per reporter in the
  // disputed cell, so this naturally captures all of them independently.
  await context.db
    .insert(slashes)
    .values({
      epochId:         Number(epochId),
      challengeType:   toChallengeType(challengeType),
      offender,
      slashAmount,
      fishermanReward,
      blockNumber:     event.block.number,
      txHash:          event.transaction.hash,
    })
    .onConflictDoNothing();

  // Update offender totals. Only one of these will find a matching row
  // (reporters and relays are disjoint sets), so both updates are safe to run.
  await context.db
    .update(reporters, { address: offender })
    .set((row) => ({
      totalSlashed: row.totalSlashed + slashAmount,
      isSlashed:    true,
    }));

  await context.db
    .update(relays, { address: offender })
    .set((row) => ({ totalSlashed: row.totalSlashed + slashAmount }));
});

// ── Reporter staking ─────────────────────────────────────────────────────────

ponder.on("VayuEpochSettlement:Staked", async ({ event, context }) => {
  const { staker, reporter, amount } = event.args;

  await context.db
    .insert(reporters)
    .values({
      address: reporter,
      staker,
      stake: amount,
    })
    .onConflictDoUpdate((row) => ({
      staker,
      stake: row.stake + amount,
    }));
});

ponder.on("VayuEpochSettlement:UnstakeInitiated", async ({ event, context }) => {
  const { account, amount, withdrawableAt } = event.args;

  await context.db
    .update(reporters, { address: account })
    .set((row) => ({
      stake:          row.stake - amount,
      pendingUnstake: row.pendingUnstake + amount,
      withdrawableAt: Number(withdrawableAt),
    }));
});

ponder.on("VayuEpochSettlement:Withdrawn", async ({ event, context }) => {
  const { account, amount } = event.args;

  await context.db
    .update(reporters, { address: account })
    .set((row) => ({
      pendingUnstake: row.pendingUnstake - amount,
      withdrawableAt: null,
    }));
});

// ── Relay lifecycle ───────────────────────────────────────────────────────────

ponder.on("VayuEpochSettlement:RelayRegistered", async ({ event, context }) => {
  const { relay, stake } = event.args;

  await context.db
    .insert(relays)
    .values({
      address:         relay,
      stake,
      isActive:        true,
      registeredAt:    Number(event.block.timestamp),
      registeredBlock: event.block.number,
      registeredTx:    event.transaction.hash,
    })
    .onConflictDoNothing();
});

ponder.on("VayuEpochSettlement:RelayDeactivated", async ({ event, context }) => {
  const { relay } = event.args;

  await context.db
    .update(relays, { address: relay })
    .set({ isActive: false });
});
