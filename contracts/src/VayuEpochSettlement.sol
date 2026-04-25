// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {MerkleProof} from "@openzeppelin/contracts/utils/cryptography/MerkleProof.sol";
import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {Pausable} from "@openzeppelin/contracts/utils/Pausable.sol";

import {VayuTypes} from "./types/VayuTypes.sol";
import {VayuRewards} from "./VayuRewards.sol";

/// @title VayuEpochSettlement
/// @notice Core protocol contract. Accepts epoch commitments from relays,
///         manages staking/slashing, and distributes rewards via Merkle proofs.
contract VayuEpochSettlement is Ownable, Pausable {
    using SafeERC20 for IERC20;

    // ─────────────────────────────────────────────────────────────────────────
    // Immutables & State
    // ─────────────────────────────────────────────────────────────────────────

    IERC20       public immutable TOKEN;
    VayuRewards  public immutable REWARDS_POOL;
    address      public treasury;

    uint256 public constant MIN_REPORTER_STAKE = 100 * 1e18;
    uint256 public constant MIN_RELAY_STAKE = 10_000 * 1e18;
    uint32  public constant REPORTER_UNSTAKE_COOLDOWN = 7 days;
    uint32  public constant RELAY_UNSTAKE_COOLDOWN = 14 days;

    bytes32 public immutable DOMAIN_SEPARATOR;

    // ── Epochs ──
    mapping(uint32 => VayuTypes.EpochCommitment) public epochCommitments;

    // ── Reward Claims ──
    // keccak256(epochId, reporter, h3Index) => claimed
    mapping(bytes32 => bool) private _claimed;

    // ── Per-epoch token balance tracking ──
    mapping(uint32 => uint256) public epochBalance;

    // ── Reporter Staking ──
    struct StakeInfo {
        uint256 active;
        uint256 pending;
        uint64  withdrawableAt;
    }
    mapping(address => StakeInfo) public reporterStakes;
    // Who staked on behalf of a reporter (for unstake/withdraw auth)
    mapping(address => address) public reporterStaker;

    // ── Relay Staking ──
    struct RelayInfo {
        uint256 stake;
        bool    active;
        uint256 pendingUnstake;
        uint64  withdrawableAt;
    }
    mapping(address => RelayInfo) public relayInfo;

    // ── Penalty list tracking (for challengePenaltyList) ──
    // penaltySlashed[epochId][reporter] = true if reporter was slashed via
    // the penalty list in that epoch. Reset on successful challenge.
    mapping(uint32 => mapping(address => bool)) public penaltySlashed;

    // ── Per-epoch reward challenge guard ──
    // epochRewardChallenged[epochId] = true once a successful reward computation
    // challenge has been resolved for that epoch. Prevents a relay from being
    // repeatedly slashed on the same epoch within the challenge window.
    mapping(uint32 => bool) public epochRewardChallenged;

    // ── Per-epoch-per-cell spatial anomaly guard ──
    // epochCellSpatialChallenged[epochId][cellId] = true once a successful
    // spatial anomaly challenge has been resolved for that epoch+cell pair.
    // Prevents the same set of reporters from being slashed multiple times for
    // the same anomalous reading within the challenge window.
    mapping(uint32 => mapping(uint64 => bool)) public epochCellSpatialChallenged;

    // ── Per-epoch-per-reporter duplicate location challenge guard ──
    // epochDuplicateLocationChallenged[epochId][reporter] = true once a
    // successful duplicate location challenge has been resolved for that
    // epoch+reporter pair. Prevents the same reporter being repeatedly drained
    // within the challenge window.
    mapping(uint32 => mapping(address => bool)) public epochDuplicateLocationChallenged;

    // ─────────────────────────────────────────────────────────────────────────
    // Events
    // ─────────────────────────────────────────────────────────────────────────

    event EpochCommitted(
        uint32 indexed epochId, address indexed relay,
        bytes32 dataRoot, bytes32 rewardRoot, string ipfsCid,
        uint32 activeCells, uint32 totalReadings
    );
    event RewardClaimed(uint32 indexed epochId, address indexed reporter, uint64 indexed h3Index, uint256 amount);
    event EpochSwept(uint32 indexed epochId, uint256 amount);
    event Slashed(address indexed offender, uint256 slashAmount, uint256 fishermanReward, VayuTypes.ChallengeType challengeType, uint32 epochId);
    event ChallengeSubmitted(uint32 indexed epochId, address indexed challenger, VayuTypes.ChallengeType challengeType);
    event ChallengeResolved(uint32 indexed epochId, address indexed challenger, VayuTypes.ChallengeType challengeType, bool succeeded);
    event Staked(address indexed staker, address indexed reporter, uint256 amount);
    event UnstakeInitiated(address indexed account, uint256 amount, uint64 withdrawableAt);
    event Withdrawn(address indexed account, uint256 amount);
    event RelayRegistered(address indexed relay, uint256 stake);
    event RelayDeactivated(address indexed relay);
    event TreasuryUpdated(address indexed oldTreasury, address indexed newTreasury);

    // ─────────────────────────────────────────────────────────────────────────
    // Errors
    // ─────────────────────────────────────────────────────────────────────────

    error NotActiveRelay();
    error EpochAlreadyCommitted(uint32 epochId);
    error ChallengeWindowOpen();
    error ClaimExpired();
    error AlreadyClaimed();
    error InvalidMerkleProof();
    error EpochNotCommitted();
    error EpochNotExpired();
    error EpochAlreadySwept();
    error InsufficientStake();
    error NoPendingWithdrawal();
    error CooldownNotElapsed();
    error RelayAlreadyRegistered();
    error RelayNotRegistered();
    error PendingWithdrawalExists();
    error NotStaker();
    error ZeroAmount();
    error ZeroAddress();
    error ChallengeWindowClosed();
    error SameReporterRequired();
    error SameCellNotAllowed();
    error EpochMismatch();
    error NotAnomaly();
    error EmptyArray();
    error ReporterNotPenalized();
    error ProofEpochOutOfRange();
    error ReporterMismatch();
    error EpochAlreadyChallenged();
    error NoDiscrepancyFound();
    error ArrayLengthMismatch();
    error CellMismatch();

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────

    constructor(
        address _token,
        address _rewardsPool,
        address _treasury
    ) Ownable(msg.sender) {
        if (_token == address(0)) revert ZeroAddress();
        if (_rewardsPool == address(0)) revert ZeroAddress();
        if (_treasury == address(0)) revert ZeroAddress();

        TOKEN = IERC20(_token);
        REWARDS_POOL = VayuRewards(_rewardsPool);
        treasury = _treasury;

        // Build the EIP-712 domain separator at deploy time so signed
        // messages are scoped to this contract + chain (replay protection).
        DOMAIN_SEPARATOR = keccak256(abi.encode(
            VayuTypes.EIP712_DOMAIN_TYPEHASH,
            keccak256(bytes(VayuTypes.DOMAIN_NAME)),
            keccak256(bytes(VayuTypes.DOMAIN_VERSION)),
            block.chainid,
            address(this)
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Epoch Commitment
    // ─────────────────────────────────────────────────────────────────────────

    function commitEpoch(
        uint32 epochId,
        bytes32 dataRoot,
        bytes32 rewardRoot,
        string calldata ipfsCid,
        uint32 activeCells,
        uint32 totalReadings,
        address[] calldata penaltyList
    ) external whenNotPaused {
        if (!relayInfo[msg.sender].active) revert NotActiveRelay();
        if (epochCommitments[epochId].committedAt != 0) revert EpochAlreadyCommitted(epochId);

        // Pull epoch budget from rewards pool
        uint256 released = REWARDS_POOL.releaseEpochBudget(epochId);

        // Deduct relay fee: (totalBudget * feeBps) / 10_000 gives the
        // relay's cut; remainder goes to the epoch reward pool.
        uint256 relayFee = (released * VayuTypes.RELAY_FEE_BPS) / VayuTypes.BPS_DENOMINATOR;
        uint256 rewardBudget = released - relayFee;

        epochBalance[epochId] = rewardBudget;

        epochCommitments[epochId] = VayuTypes.EpochCommitment({
            dataRoot: dataRoot,
            rewardRoot: rewardRoot,
            ipfsCid: ipfsCid,
            relay: msg.sender,
            committedAt: uint64(block.timestamp),
            totalReadings: totalReadings,
            activeCells: activeCells,
            finalized: false,
            swept: false
        });

        // Auto-slash reporters on the penalty list (effects only — batch transfer below).
        // Duplicate detection uses an O(n²) scan over calldata rather than a
        // storage/transient-storage mapping. For the expected penalty list sizes
        // (< ~70 entries per epoch) this is cheaper: calldata reads cost ~3 gas
        // each vs 20k+ gas per SSTORE or ~200 gas per TSTORE slot.
        uint256 totalPenalty;
        for (uint256 i = 0; i < penaltyList.length; i++) {
            address reporter = penaltyList[i];

            // Skip duplicate entries to prevent double-slashing
            bool isDuplicate;
            for (uint256 j = 0; j < i; j++) {
                if (penaltyList[j] == reporter) {
                    isDuplicate = true;
                    break;
                }
            }
            if (isDuplicate) continue;

            uint256 reporterStakeAmt = reporterStakes[reporter].active;
            if (reporterStakeAmt > 0) {
                // Slash a fixed percentage of the reporter's active stake.
                // BPS math: (stake * rateBps) / 10_000 = slashed amount.
                // Slashed tokens go to treasury (no fisherman in auto-slash).
                uint256 slashAmt = (reporterStakeAmt * VayuTypes.SLASH_REPORTER_CONSECUTIVE_ZEROS) / VayuTypes.BPS_DENOMINATOR;
                reporterStakes[reporter].active -= slashAmt;
                penaltySlashed[epochId][reporter] = true;
                totalPenalty += slashAmt;
                emit Slashed(reporter, slashAmt, 0, VayuTypes.ChallengeType.PenaltyListFraud, epochId);
            }
        }

        TOKEN.safeTransfer(msg.sender, relayFee);
        if (totalPenalty > 0) {
            TOKEN.safeTransfer(treasury, totalPenalty);
        }

        emit EpochCommitted(epochId, msg.sender, dataRoot, rewardRoot, ipfsCid, activeCells, totalReadings);
    }

    function getEpochCommitment(uint32 epochId) external view returns (VayuTypes.EpochCommitment memory) {
        return epochCommitments[epochId];
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reward Claims
    // ─────────────────────────────────────────────────────────────────────────

    function claimReward(
        uint32 epochId,
        uint64 h3Index,
        uint256 amount,
        bytes32[] calldata proof
    ) external whenNotPaused {
        VayuTypes.EpochCommitment storage epoch = epochCommitments[epochId];
        if (epoch.committedAt == 0) revert EpochNotCommitted();
        // Claims only open after the challenge window closes and before the
        // 90-day expiry — this gives fishermen time to dispute first.
        if (block.timestamp < epoch.committedAt + VayuTypes.CHALLENGE_WINDOW) revert ChallengeWindowOpen();
        if (block.timestamp > epoch.committedAt + VayuTypes.CLAIM_EXPIRY) revert ClaimExpired();

        // Derive a unique claim key from (epoch, caller, cell) to prevent
        // double-claiming the same cell reward in the same epoch.
        // forge-lint: disable-next-line(asm-keccak256) — readability over ~30 gas saving in claim key derivation
        bytes32 claimKey = keccak256(abi.encodePacked(epochId, msg.sender, h3Index));
        if (_claimed[claimKey]) revert AlreadyClaimed();

        // Reconstruct the expected Merkle leaf and verify the caller's
        // inclusion proof against the relay-committed reward root.
        bytes32 leaf = VayuTypes.rewardLeaf(msg.sender, epochId, h3Index, amount);
        if (!MerkleProof.verify(proof, epoch.rewardRoot, leaf)) revert InvalidMerkleProof();

        _claimed[claimKey] = true;
        epochBalance[epochId] -= amount;
        TOKEN.safeTransfer(msg.sender, amount);

        emit RewardClaimed(epochId, msg.sender, h3Index, amount);
    }

    function isClaimed(uint32 epochId, address reporter, uint64 h3Index) external view returns (bool) {
        return _claimed[keccak256(abi.encodePacked(epochId, reporter, h3Index))];
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sweep Expired
    // ─────────────────────────────────────────────────────────────────────────

    function sweepExpired(uint32 epochId) external {
        VayuTypes.EpochCommitment storage epoch = epochCommitments[epochId];
        if (epoch.committedAt == 0) revert EpochNotCommitted();
        if (block.timestamp <= epoch.committedAt + VayuTypes.CLAIM_EXPIRY) revert EpochNotExpired();
        if (epoch.swept) revert EpochAlreadySwept();

        epoch.swept = true;
        uint256 remaining = epochBalance[epochId];
        if (remaining > 0) {
            epochBalance[epochId] = 0;
            TOKEN.safeTransfer(treasury, remaining);
        }
        emit EpochSwept(epochId, remaining);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Challenge: Spatial Anomaly
    // ─────────────────────────────────────────────────────────────────────────

    function challengeSpatialAnomaly(
        uint32 epochId,
        uint64 disputedCell,
        VayuTypes.AQIReading[] calldata cellReadings,
        bytes32[][] calldata cellProofs,
        VayuTypes.AQIReading[] calldata neighbourReadings,
        bytes32[][] calldata neighbourProofs
    ) external whenNotPaused {
        if (cellReadings.length == 0 || neighbourReadings.length == 0) revert EmptyArray();

        VayuTypes.EpochCommitment storage epoch = epochCommitments[epochId];
        if (epoch.committedAt == 0) revert EpochNotCommitted();
        if (block.timestamp > epoch.committedAt + VayuTypes.CHALLENGE_WINDOW) revert ChallengeWindowClosed();
        if (epochCellSpatialChallenged[epochId][disputedCell]) revert EpochAlreadyChallenged();

        // Verify cell reading proofs. Each reading must belong to the challenged
        // epoch and the specific disputed cell to prevent cross-cell proof recycling.
        for (uint256 i = 0; i < cellReadings.length; i++) {
            if (cellReadings[i].h3Index != disputedCell) revert CellMismatch();
            if (cellReadings[i].epochId  != epochId)     revert EpochMismatch();
            bytes32 leaf = VayuTypes.dataLeaf(cellReadings[i]);
            if (!MerkleProof.verify(cellProofs[i], epoch.dataRoot, leaf)) revert InvalidMerkleProof();
        }

        // Verify neighbour reading proofs. Neighbours must belong to this epoch
        // and be from a different cell than the disputed one — using readings from
        // the same cell as both cell and neighbour would bias the comparison.
        for (uint256 i = 0; i < neighbourReadings.length; i++) {
            if (neighbourReadings[i].h3Index == disputedCell) revert SameCellNotAllowed();
            if (neighbourReadings[i].epochId  != epochId)     revert EpochMismatch();
            bytes32 leaf = VayuTypes.dataLeaf(neighbourReadings[i]);
            if (!MerkleProof.verify(neighbourProofs[i], epoch.dataRoot, leaf)) revert InvalidMerkleProof();
        }

        // Compare disputed cell's median AQI against its neighbours' mean.
        uint256 cellMedian = _computeMedian(cellReadings);
        uint256 neighbourMean = _computeMean(neighbourReadings);

        // Absolute difference must exceed SPATIAL_TOLERANCE_AQI to qualify
        // as an anomaly — below that threshold the deviation is acceptable.
        uint256 diff = cellMedian > neighbourMean ? cellMedian - neighbourMean : neighbourMean - cellMedian;
        if (diff <= VayuTypes.SPATIAL_TOLERANCE_AQI) revert NotAnomaly();

        epochCellSpatialChallenged[epochId][disputedCell] = true;

        emit ChallengeSubmitted(epochId, msg.sender, VayuTypes.ChallengeType.SpatialAnomaly);

        // Slash all reporters in the disputed cell. Dedup to avoid double-slashing
        // a reporter who appears more than once in the fisherman's reading list.
        uint256 totalSlashed;
        for (uint256 i = 0; i < cellReadings.length; i++) {
            address reporter = cellReadings[i].reporter;

            // O(n²) duplicate scan over calldata — acceptable for the small reading
            // counts expected per cell (< ~20) and cheaper than a storage mapping.
            bool isDuplicate;
            for (uint256 j = 0; j < i; j++) {
                if (cellReadings[j].reporter == reporter) {
                    isDuplicate = true;
                    break;
                }
            }
            if (isDuplicate) continue;

            uint256 stake = reporterStakes[reporter].active;
            if (stake > 0) {
                // BPS slash: (stake * slashRateBps) / 10_000
                uint256 slashAmt = (stake * VayuTypes.SLASH_REPORTER_FISHERMAN) / VayuTypes.BPS_DENOMINATOR;
                reporterStakes[reporter].active -= slashAmt;
                totalSlashed += slashAmt;
                emit Slashed(reporter, slashAmt, 0, VayuTypes.ChallengeType.SpatialAnomaly, epochId);
            }
        }

        // Split slashed tokens: FISHERMAN_SHARE% to the challenger who
        // proved the anomaly, remainder to treasury.
        uint256 fishermanReward = (totalSlashed * VayuTypes.FISHERMAN_SHARE) / VayuTypes.BPS_DENOMINATOR;
        if (fishermanReward > 0) {
            TOKEN.safeTransfer(msg.sender, fishermanReward);
        }
        uint256 treasuryShare = totalSlashed - fishermanReward;
        if (treasuryShare > 0) {
            TOKEN.safeTransfer(treasury, treasuryShare);
        }

        emit ChallengeResolved(epochId, msg.sender, VayuTypes.ChallengeType.SpatialAnomaly, true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Challenge: Duplicate Location
    // ─────────────────────────────────────────────────────────────────────────

    function challengeDuplicateLocation(
        uint32 epochId,
        VayuTypes.AQIReading calldata reading1,
        bytes32[] calldata proof1,
        VayuTypes.AQIReading calldata reading2,
        bytes32[] calldata proof2
    ) external whenNotPaused {
        if (reading1.reporter != reading2.reporter) revert SameReporterRequired();

        VayuTypes.EpochCommitment storage epoch = epochCommitments[epochId];
        if (epoch.committedAt == 0) revert EpochNotCommitted();
        if (block.timestamp > epoch.committedAt + VayuTypes.CHALLENGE_WINDOW) revert ChallengeWindowClosed();
        if (epochDuplicateLocationChallenged[epochId][reading1.reporter]) revert EpochAlreadyChallenged();

        // Verify proofs
        bytes32 leaf1 = VayuTypes.dataLeaf(reading1);
        bytes32 leaf2 = VayuTypes.dataLeaf(reading2);
        if (!MerkleProof.verify(proof1, epoch.dataRoot, leaf1)) revert InvalidMerkleProof();
        if (!MerkleProof.verify(proof2, epoch.dataRoot, leaf2)) revert InvalidMerkleProof();

        // Check cells are different and readings are actually from this epoch
        if (reading1.h3Index == reading2.h3Index) revert SameCellNotAllowed();
        if (reading1.epochId != epochId || reading2.epochId != epochId) revert EpochMismatch();

        emit ChallengeSubmitted(epochId, msg.sender, VayuTypes.ChallengeType.DuplicateLocation);
        epochDuplicateLocationChallenged[epochId][reading1.reporter] = true;

        // Slash reporter
        address reporter = reading1.reporter;
        uint256 stake = reporterStakes[reporter].active;
        if (stake > 0) {
            // Slash the reporter for submitting from two different H3 cells
            // in the same epoch — physically impossible, so penalize.
            uint256 slashAmt = (stake * VayuTypes.SLASH_REPORTER_DUPLICATE_LOCATION) / VayuTypes.BPS_DENOMINATOR;
            reporterStakes[reporter].active -= slashAmt;

            // Split: fisherman bounty + remainder to treasury
            uint256 fishermanReward = (slashAmt * VayuTypes.FISHERMAN_SHARE) / VayuTypes.BPS_DENOMINATOR;
            TOKEN.safeTransfer(msg.sender, fishermanReward);
            TOKEN.safeTransfer(treasury, slashAmt - fishermanReward);

            emit Slashed(reporter, slashAmt, fishermanReward, VayuTypes.ChallengeType.DuplicateLocation, epochId);
        }

        emit ChallengeResolved(epochId, msg.sender, VayuTypes.ChallengeType.DuplicateLocation, true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Challenge: Reward Computation
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Challenge a relay's reward computation for a specific cell.
    ///
    /// The fisherman must prove two things simultaneously:
    ///   1. DATA PROOF   — which reporters contributed readings for `disputedCell`
    ///                     (Merkle proofs into `epoch.dataRoot`).
    ///   2. REWARD PROOF — which reporters the relay paid for `disputedCell`,
    ///                     and at what amounts (Merkle proofs into `epoch.rewardRoot`).
    ///
    /// A discrepancy is established when at least one address in `claimedReporters`
    /// (proven present in the reward tree) has no corresponding reading for
    /// `disputedCell` in `cellReadings` (the data tree). This proves the relay
    /// paid a reporter who submitted no data — an unambiguously fraudulent reward.
    ///
    /// Each epoch may only be challenged once; subsequent attempts revert with
    /// `EpochAlreadyChallenged` to prevent stake-draining through repeated slashes.
    function challengeRewardComputation(
        uint32 epochId,
        uint64 disputedCell,
        VayuTypes.AQIReading[] calldata cellReadings,
        bytes32[][] calldata cellProofs,
        address[] calldata claimedReporters,
        uint256[] calldata claimedAmounts,
        bytes32[][] calldata rewardProofs
    ) external whenNotPaused {
        VayuTypes.EpochCommitment storage epoch = epochCommitments[epochId];
        if (epoch.committedAt == 0) revert EpochNotCommitted();
        if (block.timestamp > epoch.committedAt + VayuTypes.CHALLENGE_WINDOW) revert ChallengeWindowClosed();
        if (epochRewardChallenged[epochId]) revert EpochAlreadyChallenged();
        if (claimedReporters.length == 0) revert EmptyArray();
        if (claimedAmounts.length != claimedReporters.length) revert ArrayLengthMismatch();
        if (rewardProofs.length != claimedReporters.length) revert ArrayLengthMismatch();
        if (cellReadings.length == 0) revert EmptyArray();
        if (cellProofs.length != cellReadings.length) revert ArrayLengthMismatch();

        // Step 1: Verify data proofs — proves what readings exist in the data tree
        // for the disputed cell. This establishes the ground truth of who submitted data.
        for (uint256 i = 0; i < cellReadings.length; i++) {
            bytes32 leaf = VayuTypes.dataLeaf(cellReadings[i]);
            if (!MerkleProof.verify(cellProofs[i], epoch.dataRoot, leaf)) revert InvalidMerkleProof();
        }

        // Step 2: Verify reward proofs — proves the relay actually assigned these
        // rewards in the reward tree. Without this, a fisherman could fabricate
        // claims about what the relay paid.
        for (uint256 i = 0; i < claimedReporters.length; i++) {
            bytes32 rLeaf = VayuTypes.rewardLeaf(claimedReporters[i], epochId, disputedCell, claimedAmounts[i]);
            if (!MerkleProof.verify(rewardProofs[i], epoch.rewardRoot, rLeaf)) revert InvalidMerkleProof();
        }

        // Step 3: Discrepancy check — at least one rewarded reporter must be absent
        // from the data readings for the disputed cell. A relay that pays reporters
        // who submitted no data is committing provable fraud.
        bool discrepancyFound = false;
        for (uint256 i = 0; i < claimedReporters.length; i++) {
            bool hasReading = false;
            for (uint256 j = 0; j < cellReadings.length; j++) {
                if (cellReadings[j].reporter == claimedReporters[i] &&
                    cellReadings[j].h3Index  == disputedCell) {
                    hasReading = true;
                    break;
                }
            }
            if (!hasReading) {
                discrepancyFound = true;
                break;
            }
        }
        if (!discrepancyFound) revert NoDiscrepancyFound();

        epochRewardChallenged[epochId] = true;

        emit ChallengeSubmitted(epochId, msg.sender, VayuTypes.ChallengeType.RewardComputation);

        // Slash the relay that committed the fraudulent reward tree.
        address relay = epoch.relay;
        RelayInfo storage ri = relayInfo[relay];
        uint256 slashAmt = (ri.stake * VayuTypes.SLASH_RELAY_REWARD_COMPUTATION) / VayuTypes.BPS_DENOMINATOR;
        ri.stake -= slashAmt;

        // If the relay's remaining stake drops below the minimum, force-
        // deactivate so it can no longer commit epochs.
        if (ri.stake < MIN_RELAY_STAKE) {
            ri.active = false;
            emit RelayDeactivated(relay);
        }

        // Split: fisherman bounty + remainder to treasury
        uint256 fishermanReward = (slashAmt * VayuTypes.FISHERMAN_SHARE) / VayuTypes.BPS_DENOMINATOR;
        TOKEN.safeTransfer(msg.sender, fishermanReward);
        TOKEN.safeTransfer(treasury, slashAmt - fishermanReward);

        emit Slashed(relay, slashAmt, fishermanReward, VayuTypes.ChallengeType.RewardComputation, epochId);
        emit ChallengeResolved(epochId, msg.sender, VayuTypes.ChallengeType.RewardComputation, true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Challenge: Penalty List Fraud
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Dispute a reporter's inclusion in a penalty list.
    ///         The relay claimed the reporter had CONSECUTIVE_ZERO_SCORES_THRESHOLD
    ///         consecutive zero-score epochs. The fisherman disproves this by
    ///         providing a Merkle-proven reading from the reporter in an epoch
    ///         within that lookback window. If the relay included the reading in
    ///         the data tree, it acknowledged the reporter was active — putting
    ///         them on the penalty list for inactivity is self-contradictory.
    function challengePenaltyList(
        uint32 penaltyEpochId,
        address reporter,
        uint32 proofEpochId,
        VayuTypes.AQIReading calldata reading,
        bytes32[] calldata proof
    ) external whenNotPaused {
        if (reading.reporter != reporter) revert ReporterMismatch();
        if (reading.epochId != proofEpochId) revert EpochMismatch();

        uint32 threshold = VayuTypes.CONSECUTIVE_ZERO_SCORES_THRESHOLD;
        uint32 windowStart = penaltyEpochId > threshold ? penaltyEpochId - threshold : 0;
        if (proofEpochId <= windowStart || proofEpochId > penaltyEpochId) revert ProofEpochOutOfRange();

        VayuTypes.EpochCommitment storage penaltyEpoch = epochCommitments[penaltyEpochId];
        if (penaltyEpoch.committedAt == 0) revert EpochNotCommitted();
        if (block.timestamp > penaltyEpoch.committedAt + VayuTypes.CHALLENGE_WINDOW) revert ChallengeWindowClosed();
        if (!penaltySlashed[penaltyEpochId][reporter]) revert ReporterNotPenalized();

        VayuTypes.EpochCommitment storage proofEpoch = epochCommitments[proofEpochId];
        if (proofEpoch.committedAt == 0) revert EpochNotCommitted();

        bytes32 leaf = VayuTypes.dataLeaf(reading);
        if (!MerkleProof.verify(proof, proofEpoch.dataRoot, leaf)) revert InvalidMerkleProof();

        emit ChallengeSubmitted(penaltyEpochId, msg.sender, VayuTypes.ChallengeType.PenaltyListFraud);

        // Mark penalty as disputed so it can't be re-challenged
        penaltySlashed[penaltyEpochId][reporter] = false;

        // Slash the relay that submitted the fraudulent penalty list
        address relay = penaltyEpoch.relay;
        RelayInfo storage ri = relayInfo[relay];
        uint256 slashAmt = (ri.stake * VayuTypes.SLASH_RELAY_PENALTY_LIST) / VayuTypes.BPS_DENOMINATOR;
        ri.stake -= slashAmt;

        if (ri.stake < MIN_RELAY_STAKE) {
            ri.active = false;
            emit RelayDeactivated(relay);
        }

        // Split: fisherman bounty + remainder to treasury
        uint256 fishermanReward = (slashAmt * VayuTypes.FISHERMAN_SHARE) / VayuTypes.BPS_DENOMINATOR;
        TOKEN.safeTransfer(msg.sender, fishermanReward);
        TOKEN.safeTransfer(treasury, slashAmt - fishermanReward);

        emit Slashed(relay, slashAmt, fishermanReward, VayuTypes.ChallengeType.PenaltyListFraud, penaltyEpochId);
        emit ChallengeResolved(penaltyEpochId, msg.sender, VayuTypes.ChallengeType.PenaltyListFraud, true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reporter Staking
    // ─────────────────────────────────────────────────────────────────────────

    function stakeFor(address reporter, uint256 amount) external whenNotPaused {
        if (reporter == address(0)) revert ZeroAddress();
        if (amount == 0) revert ZeroAmount();

        // Record the staker if first time
        if (reporterStakes[reporter].active == 0 && reporterStaker[reporter] == address(0)) {
            reporterStaker[reporter] = msg.sender;
        }
    
        reporterStakes[reporter].active += amount;

        TOKEN.safeTransferFrom(msg.sender, address(this), amount);

        emit Staked(msg.sender, reporter, amount);
    }

    function unstakeReporter(address reporter, uint256 amount) external {
        if (amount == 0) revert ZeroAmount();
        if (reporterStaker[reporter] != msg.sender && reporter != msg.sender) revert NotStaker();
        if (reporterStakes[reporter].active < amount) revert InsufficientStake();

        // Move tokens from active → pending and start the cooldown timer.
        // Pending stake cannot be slashed but also cannot back the reporter.
        reporterStakes[reporter].active -= amount;
        reporterStakes[reporter].pending += amount;
        reporterStakes[reporter].withdrawableAt = uint64(block.timestamp + REPORTER_UNSTAKE_COOLDOWN);

        emit UnstakeInitiated(reporter, amount, reporterStakes[reporter].withdrawableAt);
    }

    function withdrawReporter(address reporter) external {
        if (reporterStaker[reporter] != msg.sender && reporter != msg.sender) revert NotStaker();

        StakeInfo storage si = reporterStakes[reporter];
        if (si.pending == 0) revert NoPendingWithdrawal();
        if (block.timestamp < si.withdrawableAt) revert CooldownNotElapsed();

        uint256 amount = si.pending;
        si.pending = 0;
        si.withdrawableAt = 0;

        TOKEN.safeTransfer(msg.sender, amount);
        emit Withdrawn(reporter, amount);
    }

    function reporterStake(address reporter) external view returns (uint256) {
        return reporterStakes[reporter].active;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Relay Staking
    // ─────────────────────────────────────────────────────────────────────────

    function registerRelay() external whenNotPaused {
        if (relayInfo[msg.sender].active) revert RelayAlreadyRegistered();
        if (relayInfo[msg.sender].pendingUnstake > 0) revert PendingWithdrawalExists();

        // A relay that was force-deactivated by a slash may still have residual
        // stake sitting in the contract.  Credit it toward MIN_RELAY_STAKE so
        // those tokens are not orphaned; only pull the shortfall from the wallet.
        uint256 existing = relayInfo[msg.sender].stake;
        uint256 topUp    = existing < MIN_RELAY_STAKE ? MIN_RELAY_STAKE - existing : 0;

        relayInfo[msg.sender] = RelayInfo({
            stake: MIN_RELAY_STAKE,
            active: true,
            pendingUnstake: 0,
            withdrawableAt: 0
        });

        if (topUp > 0) {
            TOKEN.safeTransferFrom(msg.sender, address(this), topUp);
        }

        emit RelayRegistered(msg.sender, MIN_RELAY_STAKE);
    }

    function deregisterRelay() external {
        RelayInfo storage ri = relayInfo[msg.sender];
        if (!ri.active) revert RelayNotRegistered();

        ri.active = false;
        ri.pendingUnstake = ri.stake;
        ri.stake = 0;
        ri.withdrawableAt = uint64(block.timestamp + RELAY_UNSTAKE_COOLDOWN);

        emit RelayDeactivated(msg.sender);
        emit UnstakeInitiated(msg.sender, ri.pendingUnstake, ri.withdrawableAt);
    }

    function withdrawRelay() external {
        RelayInfo storage ri = relayInfo[msg.sender];
        if (ri.pendingUnstake == 0) revert NoPendingWithdrawal();
        if (block.timestamp < ri.withdrawableAt) revert CooldownNotElapsed();

        uint256 amount = ri.pendingUnstake;
        ri.pendingUnstake = 0;
        ri.withdrawableAt = 0;

        TOKEN.safeTransfer(msg.sender, amount);
        emit Withdrawn(msg.sender, amount);
    }

    function isActiveRelay(address relay) external view returns (bool) {
        return relayInfo[relay].active;
    }

    function relayStake(address relay) external view returns (uint256) {
        return relayInfo[relay].stake;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Admin
    // ─────────────────────────────────────────────────────────────────────────

    function setTreasury(address _treasury) external onlyOwner {
        if (_treasury == address(0)) revert ZeroAddress();
        emit TreasuryUpdated(treasury, _treasury);
        treasury = _treasury;
    }

    function pause() external onlyOwner { _pause(); }
    function unpause() external onlyOwner { _unpause(); }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /// @dev Compute median AQI from an array of readings (in-place-style sort).
    function _computeMedian(VayuTypes.AQIReading[] calldata readings) internal pure returns (uint256) {
        uint256 len = readings.length;
        uint16[] memory values = new uint16[](len);
        for (uint256 i = 0; i < len; i++) {
            values[i] = readings[i].aqi;
        }
        // Simple insertion sort (fine for small arrays in challenge context)
        for (uint256 i = 1; i < len; i++) {
            uint16 key = values[i];
            uint256 j = i;
            while (j > 0 && values[j - 1] > key) {
                values[j] = values[j - 1];
                j--;
            }
            values[j] = key;
        }
        return values[len / 2];
    }

    /// @dev Compute mean AQI from an array of readings.
    function _computeMean(VayuTypes.AQIReading[] calldata readings) internal pure returns (uint256) {
        uint256 sum;
        for (uint256 i = 0; i < readings.length; i++) {
            sum += readings[i].aqi;
        }
        return sum / readings.length;
    }
}
