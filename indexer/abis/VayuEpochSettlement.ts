// ABI for VayuEpochSettlement — events only.
// ChallengeType enum values: 0=SpatialAnomaly 1=RewardComputation 2=DataIntegrity 3=DuplicateLocation 4=PenaltyListFraud
// The enum is encoded as uint8 in the ABI.

export const VayuEpochSettlementAbi = [
  // ── Core epoch lifecycle ──────────────────────────────────────────────────
  {
    type: "event",
    name: "EpochCommitted",
    inputs: [
      { indexed: true,  name: "epochId",      type: "uint32"  },
      { indexed: true,  name: "relay",         type: "address" },
      { indexed: false, name: "dataRoot",      type: "bytes32" },
      { indexed: false, name: "rewardRoot",    type: "bytes32" },
      { indexed: false, name: "ipfsCid",       type: "string"  },
      { indexed: false, name: "activeCells",   type: "uint32"  },
      { indexed: false, name: "totalReadings", type: "uint32"  },
    ],
  },
  {
    type: "event",
    name: "EpochSwept",
    inputs: [
      { indexed: true,  name: "epochId", type: "uint32"  },
      { indexed: false, name: "amount",  type: "uint256" },
    ],
  },

  // ── Reward claims ─────────────────────────────────────────────────────────
  {
    type: "event",
    name: "RewardClaimed",
    inputs: [
      { indexed: true,  name: "epochId",  type: "uint32"  },
      { indexed: true,  name: "reporter", type: "address" },
      { indexed: true,  name: "h3Index",  type: "uint64"  },
      { indexed: false, name: "amount",   type: "uint256" },
    ],
  },

  // ── Challenges & slashing ─────────────────────────────────────────────────
  {
    type: "event",
    name: "ChallengeSubmitted",
    inputs: [
      { indexed: true,  name: "epochId",       type: "uint32"  },
      { indexed: true,  name: "challenger",    type: "address" },
      { indexed: false, name: "challengeType", type: "uint8"   },
    ],
  },
  {
    type: "event",
    name: "ChallengeResolved",
    inputs: [
      { indexed: true,  name: "epochId",       type: "uint32"  },
      { indexed: true,  name: "challenger",    type: "address" },
      { indexed: false, name: "challengeType", type: "uint8"   },
      { indexed: false, name: "succeeded",     type: "bool"    },
    ],
  },
  {
    type: "event",
    name: "Slashed",
    inputs: [
      { indexed: true,  name: "offender",       type: "address" },
      { indexed: false, name: "slashAmount",     type: "uint256" },
      { indexed: false, name: "fishermanReward", type: "uint256" },
      { indexed: false, name: "challengeType",   type: "uint8"   },
      { indexed: false, name: "epochId",         type: "uint32"  },
    ],
  },

  // ── Reporter staking ──────────────────────────────────────────────────────
  {
    type: "event",
    name: "Staked",
    inputs: [
      { indexed: true,  name: "staker",   type: "address" },
      { indexed: true,  name: "reporter", type: "address" },
      { indexed: false, name: "amount",   type: "uint256" },
    ],
  },
  {
    type: "event",
    name: "UnstakeInitiated",
    inputs: [
      { indexed: true,  name: "account",       type: "address" },
      { indexed: false, name: "amount",         type: "uint256" },
      { indexed: false, name: "withdrawableAt", type: "uint64"  },
    ],
  },
  {
    type: "event",
    name: "Withdrawn",
    inputs: [
      { indexed: true,  name: "account", type: "address" },
      { indexed: false, name: "amount",  type: "uint256" },
    ],
  },

  // ── Relay lifecycle ───────────────────────────────────────────────────────
  {
    type: "event",
    name: "RelayRegistered",
    inputs: [
      { indexed: true,  name: "relay", type: "address" },
      { indexed: false, name: "stake", type: "uint256" },
    ],
  },
  {
    type: "event",
    name: "RelayDeactivated",
    inputs: [
      { indexed: true, name: "relay", type: "address" },
    ],
  },
] as const;
