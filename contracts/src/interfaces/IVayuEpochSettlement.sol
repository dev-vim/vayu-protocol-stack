// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {VayuTypes} from "../types/VayuTypes.sol";

/// @title IVayuEpochSettlement
/// @notice Interface for the core Vayu protocol settlement contract.
///
///   Responsibilities:
///   - Accept epoch commitments from registered relays (commitEpoch)
///   - Allow reporters to claim earned rewards via Merkle proof (claimReward)
///   - Allow fishermen to submit fraud challenges (challengeXxx)
///   - Manage reporter and relay stakes (stake, unstake, withdraw)
///   - Enforce slashing conditions and distribute slash proceeds
///   - Sweep expired unclaimed rewards to the treasury
///
///   Access control:
///   - commitEpoch: registered relay only (msg.sender in relayStakes with
///                  stake >= MIN_RELAY_STAKE)
///   - claimReward: any address (typically the reporter themselves)
///   - challengeXxx: permissionless (any address — the fisherman role)
///   - stake / unstake / withdraw: the staking party for their own stake
///   - sweepExpired: permissionless
///   - admin functions (setTreasury, pause): owner (UUPS upgradeability)
///
///   Upgrade strategy: UUPS (EIP-1822). The token and rewards escrow
///   contracts are immutable; only this contract is upgradeable.
interface IVayuEpochSettlement {

    // ─────────────────────────────────────────────────────────────────────────
    // Events
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Emitted when a relay commits an epoch.
    event EpochCommitted(
        uint32  indexed epochId,
        address indexed relay,
        bytes32         dataRoot,
        bytes32         rewardRoot,
        string          ipfsCid,
        uint32          activeCells,
        uint32          totalReadings
    );

    /// @notice Emitted when a reporter claims their reward for an epoch.
    event RewardClaimed(
        uint32  indexed epochId,
        address indexed reporter,
        uint64  indexed h3Index,
        uint256         amount
    );

    /// @notice Emitted when expired epoch rewards are swept to the treasury.
    event EpochSwept(
        uint32  indexed epochId,
        uint256         amount
    );

    /// @notice Emitted when a reporter or relay is slashed.
    event Slashed(
        address indexed offender,
        uint256         slashAmount,
        uint256         fishermanReward,
        VayuTypes.ChallengeType challengeType,
        uint32          epochId
    );

    /// @notice Emitted when a challenge is submitted.
    event ChallengeSubmitted(
        uint32  indexed epochId,
        address indexed challenger,
        VayuTypes.ChallengeType challengeType
    );

    /// @notice Emitted when a challenge is resolved (succeeded or failed).
    event ChallengeResolved(
        uint32  indexed epochId,
        address indexed challenger,
        VayuTypes.ChallengeType challengeType,
        bool            succeeded
    );

    /// @notice Emitted when the relay corrects a reward tree after a
    ///         successful RewardComputation challenge.
    event RewardRootCorrected(
        uint32  indexed epochId,
        bytes32         correctedRoot
    );

    /// @notice Emitted when a reporter stakes tokens (for themselves or
    ///         on behalf of a device).
    event Staked(
        address indexed staker,
        address indexed reporter,
        uint256         amount
    );

    /// @notice Emitted when a reporter or relay initiates an unstake cooldown.
    event UnstakeInitiated(
        address indexed account,
        uint256         amount,
        uint64          withdrawableAt
    );

    /// @notice Emitted when a stake withdrawal is completed after cooldown.
    event Withdrawn(
        address indexed account,
        uint256         amount
    );

    /// @notice Emitted when a relay registers with the protocol.
    event RelayRegistered(
        address indexed relay,
        uint256         stake
    );

    /// @notice Emitted when a relay is deactivated (slash below minimum or
    ///         voluntary deregistration).
    event RelayDeactivated(
        address indexed relay
    );

    // ─────────────────────────────────────────────────────────────────────────
    // Epoch Commitment
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Called by a registered relay to commit an epoch's data.
    ///
    ///   The relay must have stake >= MIN_RELAY_STAKE. It computes the two
    ///   Merkle trees off-chain, pins the full data blob to IPFS, then calls
    ///   this function.
    ///
    ///   The relay may include an optional penaltyList for reporters who have
    ///   scored zero for CONSECUTIVE_ZERO_SCORES_THRESHOLD or more consecutive epochs.
    ///   These reporters are auto-slashed 5% with slash sent to treasury
    ///   (no fisherman involved).
    ///
    ///   After this call:
    ///   - epochCommitments[epochId] is written
    ///   - The relay's consecutive-zero counters are updated
    ///   - The epoch budget is transferred from AQIRewards to this contract
    ///
    /// @param epochId         Monotonically increasing epoch counter.
    /// @param dataRoot        Merkle root of all AQIReading data leaves.
    /// @param rewardRoot      Merkle root of all reward leaves.
    /// @param ipfsCid         IPFS CID of the epoch blob (JSON or CBOR).
    /// @param activeCells     Number of cells with >= MIN_REPORTERS_PER_CELL.
    /// @param totalReadings   Total readings across all cells (informational).
    /// @param penaltyList     Reporters to auto-slash for consecutive zeros.
    ///                        May be empty.
    function commitEpoch(
        uint32          epochId,
        bytes32         dataRoot,
        bytes32         rewardRoot,
        string calldata ipfsCid,
        uint32          activeCells,
        uint32          totalReadings,
        address[]       calldata penaltyList
    ) external;

    /// @notice Returns the stored commitment for a given epoch.
    function epochCommitments(uint32 epochId)
        external view returns (VayuTypes.EpochCommitment memory);

    // ─────────────────────────────────────────────────────────────────────────
    // Reward Claims
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Allows a reporter to claim earned tokens for a specific epoch
    ///         and cell, using a Merkle proof against the epoch's rewardRoot.
    ///
    ///   Requirements:
    ///   - Challenge window must be closed (committedAt + CHALLENGE_WINDOW)
    ///   - Claim must not be expired (committedAt + CLAIM_EXPIRY)
    ///   - Proof must verify against epochCommitments[epochId].rewardRoot
    ///   - Leaf (reporter, epochId, h3Index, amount) must not already be claimed
    ///
    /// @param epochId    The epoch to claim from.
    /// @param h3Index    The cell the reporter was rewarded for.
    /// @param amount     The reward amount in token wei (must match the leaf).
    /// @param proof      Merkle proof for the reward leaf.
    function claimReward(
        uint32          epochId,
        uint64          h3Index,
        uint256         amount,
        bytes32[] calldata proof
    ) external;

    /// @notice Returns true if a reward leaf has already been claimed.
    /// @param epochId    The epoch.
    /// @param reporter   The reporter address.
    /// @param h3Index    The cell.
    function isClaimed(
        uint32  epochId,
        address reporter,
        uint64  h3Index
    ) external view returns (bool);

    /// @notice Sweeps unclaimed rewards for an expired epoch back to treasury.
    ///         Permissionless — anyone may call this once CLAIM_EXPIRY has
    ///         passed for the epoch.
    /// @param epochId The epoch to sweep.
    function sweepExpired(uint32 epochId) external;

    // ─────────────────────────────────────────────────────────────────────────
    // Challenge: Spatial Anomaly
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Challenge a cell whose median AQI is inconsistent with its
    ///         H3 neighbour cells (suggests Sybil collusion in that cell).
    ///
    ///   The fisherman provides all readings for the disputed cell and all
    ///   neighbour cells, with Merkle proofs for each. The contract:
    ///   1. Verifies every proof against epochCommitments[epochId].dataRoot
    ///   2. Computes medians on-chain for the disputed cell and its neighbours
    ///   3. Checks if the disputed cell's median falls outside
    ///      (neighbourMean ± SPATIAL_TOLERANCE_AQI)
    ///   4. If anomalous: slashes all reporters IN THE DISPUTED CELL
    ///      by SLASH_REPORTER_FISHERMAN of their stake
    ///
    /// @param epochId          The epoch to challenge.
    /// @param disputedCell     The H3 cell index suspected of anomaly.
    /// @param cellReadings     All readings for the disputed cell.
    /// @param cellProofs       Paired Merkle proofs for cellReadings.
    /// @param neighbourReadings All readings for neighbour cells.
    /// @param neighbourProofs  Paired Merkle proofs for neighbourReadings.
    function challengeSpatialAnomaly(
        uint32                          epochId,
        uint64                          disputedCell,
        VayuTypes.AQIReading[] calldata cellReadings,
        bytes32[][]            calldata cellProofs,
        VayuTypes.AQIReading[] calldata neighbourReadings,
        bytes32[][]            calldata neighbourProofs
    ) external;

    // ─────────────────────────────────────────────────────────────────────────
    // Challenge: Reward Computation
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Challenge a cell where the relay computed rewards incorrectly.
    ///
    ///   The fisherman provides all readings for the disputed cell. The contract:
    ///   1. Verifies proofs against epochCommitments[epochId].dataRoot
    ///   2. Recomputes median, scores, and reward amounts on-chain
    ///   3. Rebuilds the correct reward Merkle tree for the cell
    ///   4. Compares against epochCommitments[epochId].rewardRoot
    ///   5. If different: slashes the relay by SLASH_RELAY_REWARD_COMPUTATION
    ///      and stores the correctedRoot so reporters can claim correctly
    ///
    /// @param epochId          The epoch to challenge.
    /// @param disputedCell     The H3 cell where rewards are wrong.
    /// @param cellReadings     All readings for that cell (with proofs).
    /// @param cellProofs       Paired Merkle proofs for cellReadings.
    /// @param claimedReporters Reporter addresses from the relay's reward tree.
    /// @param claimedAmounts   Corresponding reward amounts the relay committed.
    ///                         Used to identify the specific discrepancy.
    function challengeRewardComputation(
        uint32                          epochId,
        uint64                          disputedCell,
        VayuTypes.AQIReading[] calldata cellReadings,
        bytes32[][]            calldata cellProofs,
        address[]              calldata claimedReporters,
        uint256[]              calldata claimedAmounts
    ) external;

    // ─────────────────────────────────────────────────────────────────────────
    // Challenge: Duplicate Location
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Challenge a reporter who submitted readings in two cells that
    ///         are physically too far apart to be genuine in one epoch.
    ///
    ///   The fisherman provides two readings from the same reporter in the
    ///   same epoch, from cells whose H3 distance exceeds MAX_H3_TRAVEL_DISTANCE.
    ///   The contract:
    ///   1. Verifies both proofs against epochCommitments[epochId].dataRoot
    ///   2. Confirms reading1.reporter == reading2.reporter
    ///   3. Confirms h3Distance(reading1.h3Index, reading2.h3Index) > MAX_H3_TRAVEL_DISTANCE
    ///   4. Slashes reporter by SLASH_REPORTER_DUPLICATE_LOCATION
    ///
    /// @param epochId   The epoch.
    /// @param reading1  First reading (in one cell).
    /// @param proof1    Merkle proof for reading1.
    /// @param reading2  Second reading (in a distant cell).
    /// @param proof2    Merkle proof for reading2.
    function challengeDuplicateLocation(
        uint32                       epochId,
        VayuTypes.AQIReading calldata reading1,
        bytes32[]            calldata proof1,
        VayuTypes.AQIReading calldata reading2,
        bytes32[]            calldata proof2
    ) external;

    // ─────────────────────────────────────────────────────────────────────────
    // Staking — Reporter
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Stake tokens on behalf of a reporter address (typically a device).
    ///         The caller (staker) pays the tokens; the reporter is the identity
    ///         that signs readings. These may be the same address (if a user
    ///         stakes for their own browser wallet) or different (user stakes
    ///         for their edge device).
    ///
    ///   The caller must have approved this contract to spend `amount` of
    ///   the Vayu token before calling.
    ///
    /// @param reporter  The address that will sign AQI readings.
    /// @param amount    Token amount to stake (in wei).
    function stakeFor(address reporter, uint256 amount) external;

    /// @notice Begin the reporter unstake cooldown. Tokens are locked for
    ///         REPORTER_UNSTAKE_COOLDOWN (7 days) before withdrawal.
    ///         The pending amount is no longer eligible for rewards but is
    ///         still slashable during the cooldown period.
    /// @param reporter The reporter address to begin unstaking.
    /// @param amount   Amount to unstake.
    function unstakeReporter(address reporter, uint256 amount) external;

    /// @notice Withdraw reporter stake after the cooldown period has elapsed.
    /// @param reporter The reporter address.
    function withdrawReporter(address reporter) external;

    /// @notice Returns the active (non-pending) stake for a reporter.
    function reporterStake(address reporter) external view returns (uint256);

    // ─────────────────────────────────────────────────────────────────────────
    // Staking — Relay
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Register as a relay by staking MIN_RELAY_STAKE tokens.
    ///         Caller becomes eligible to call commitEpoch().
    ///         The caller must have pre-approved this contract to spend the
    ///         required token amount.
    function registerRelay() external;

    /// @notice Begin the relay unstake cooldown (14 days). Relay is immediately
    ///         deactivated and cannot submit new epochs.
    function deregisterRelay() external;

    /// @notice Withdraw relay stake after the cooldown period.
    function withdrawRelay() external;

    /// @notice Returns true if an address is a currently active (staked) relay.
    function isActiveRelay(address relay) external view returns (bool);

    /// @notice Returns the current stake of a relay.
    function relayStake(address relay) external view returns (uint256);

    // ─────────────────────────────────────────────────────────────────────────
    // Protocol Config Views
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Returns the address of the Vayu ERC-20 token.
    function token() external view returns (address);

    /// @notice Returns the address of the protocol treasury (multisig).
    function treasury() external view returns (address);

    /// @notice Returns the address of the AQIRewards escrow contract.
    function rewardsPool() external view returns (address);

    /// @notice Returns the minimum reporter stake required to earn rewards.
    function MIN_REPORTER_STAKE() external view returns (uint256);

    /// @notice Returns the minimum relay stake required to commit epochs.
    function MIN_RELAY_STAKE() external view returns (uint256);

    /// @notice Returns the EIP-712 domain separator used by this contract.
    function DOMAIN_SEPARATOR() external view returns (bytes32);
}
