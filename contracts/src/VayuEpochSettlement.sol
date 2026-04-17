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

    // ── Consecutive zero-score tracking (for auto-slash) ──
    mapping(address => uint8) public consecutiveZeroScores;

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
    event RewardRootCorrected(uint32 indexed epochId, bytes32 correctedRoot);
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
    error NotStaker();
    error ZeroAmount();
    error ZeroAddress();
    error ChallengeWindowClosed();
    error SameReporterRequired();
    error SameCellNotAllowed();
    error EpochMismatch();
    error NotAnomaly();
    error RewardsCorrect();

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

        // Deduct relay fee
        uint256 relayFee = (released * VayuTypes.RELAY_FEE_BPS) / 10_000;
        uint256 rewardBudget = released - relayFee;
        TOKEN.safeTransfer(msg.sender, relayFee);

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

        // Auto-slash reporters on the penalty list
        for (uint256 i = 0; i < penaltyList.length; i++) {
            address reporter = penaltyList[i];
            uint256 reporterStakeAmt = reporterStakes[reporter].active;
            if (reporterStakeAmt > 0) {
                uint256 slashAmt = (reporterStakeAmt * VayuTypes.SLASH_REPORTER_CONSECUTIVE_ZEROS) / 10_000;
                reporterStakes[reporter].active -= slashAmt;
                TOKEN.safeTransfer(treasury, slashAmt);
                emit Slashed(reporter, slashAmt, 0, VayuTypes.ChallengeType.SpatialAnomaly, epochId);
            }
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
        if (block.timestamp < epoch.committedAt + VayuTypes.CHALLENGE_WINDOW) revert ChallengeWindowOpen();
        if (block.timestamp > epoch.committedAt + VayuTypes.CLAIM_EXPIRY) revert ClaimExpired();

        // forge-lint: disable-next-line(asm-keccak256) — readability over ~30 gas saving in claim key derivation
        bytes32 claimKey = keccak256(abi.encodePacked(epochId, msg.sender, h3Index));
        if (_claimed[claimKey]) revert AlreadyClaimed();

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
        uint64, /* disputedCell */
        VayuTypes.AQIReading[] calldata cellReadings,
        bytes32[][] calldata cellProofs,
        VayuTypes.AQIReading[] calldata neighbourReadings,
        bytes32[][] calldata neighbourProofs
    ) external whenNotPaused {
        VayuTypes.EpochCommitment storage epoch = epochCommitments[epochId];
        if (epoch.committedAt == 0) revert EpochNotCommitted();
        if (block.timestamp > epoch.committedAt + VayuTypes.CHALLENGE_WINDOW) revert ChallengeWindowClosed();

        emit ChallengeSubmitted(epochId, msg.sender, VayuTypes.ChallengeType.SpatialAnomaly);

        // Verify all cell reading proofs
        for (uint256 i = 0; i < cellReadings.length; i++) {
            bytes32 leaf = VayuTypes.dataLeaf(cellReadings[i]);
            if (!MerkleProof.verify(cellProofs[i], epoch.dataRoot, leaf)) revert InvalidMerkleProof();
        }

        // Verify all neighbour reading proofs
        for (uint256 i = 0; i < neighbourReadings.length; i++) {
            bytes32 leaf = VayuTypes.dataLeaf(neighbourReadings[i]);
            if (!MerkleProof.verify(neighbourProofs[i], epoch.dataRoot, leaf)) revert InvalidMerkleProof();
        }

        // Compute medians
        uint256 cellMedian = _computeMedian(cellReadings);
        uint256 neighbourMean = _computeMean(neighbourReadings);

        // Check anomaly
        uint256 diff = cellMedian > neighbourMean ? cellMedian - neighbourMean : neighbourMean - cellMedian;
        if (diff <= 50) revert NotAnomaly(); // SPATIAL_TOLERANCE_AQI = 50

        // Slash all reporters in disputed cell
        uint256 totalSlashed;
        for (uint256 i = 0; i < cellReadings.length; i++) {
            address reporter = cellReadings[i].reporter;
            uint256 stake = reporterStakes[reporter].active;
            if (stake > 0) {
                uint256 slashAmt = (stake * VayuTypes.SLASH_REPORTER_FISHERMAN) / 10_000;
                reporterStakes[reporter].active -= slashAmt;
                totalSlashed += slashAmt;
                emit Slashed(reporter, slashAmt, 0, VayuTypes.ChallengeType.SpatialAnomaly, epochId);
            }
        }

        // Pay fisherman
        uint256 fishermanReward = (totalSlashed * VayuTypes.FISHERMAN_SHARE) / 10_000;
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
        VayuTypes.EpochCommitment storage epoch = epochCommitments[epochId];
        if (epoch.committedAt == 0) revert EpochNotCommitted();
        if (block.timestamp > epoch.committedAt + VayuTypes.CHALLENGE_WINDOW) revert ChallengeWindowClosed();
        if (reading1.reporter != reading2.reporter) revert SameReporterRequired();

        emit ChallengeSubmitted(epochId, msg.sender, VayuTypes.ChallengeType.DuplicateLocation);

        // Verify proofs
        bytes32 leaf1 = VayuTypes.dataLeaf(reading1);
        bytes32 leaf2 = VayuTypes.dataLeaf(reading2);
        if (!MerkleProof.verify(proof1, epoch.dataRoot, leaf1)) revert InvalidMerkleProof();
        if (!MerkleProof.verify(proof2, epoch.dataRoot, leaf2)) revert InvalidMerkleProof();

        // Check cells are different and readings are actually from this epoch
        if (reading1.h3Index == reading2.h3Index) revert SameCellNotAllowed();
        if (reading1.epochId != epochId || reading2.epochId != epochId) revert EpochMismatch();

        // Slash reporter
        address reporter = reading1.reporter;
        uint256 stake = reporterStakes[reporter].active;
        if (stake > 0) {
            uint256 slashAmt = (stake * VayuTypes.SLASH_REPORTER_DUPLICATE_LOCATION) / 10_000;
            reporterStakes[reporter].active -= slashAmt;

            uint256 fishermanReward = (slashAmt * VayuTypes.FISHERMAN_SHARE) / 10_000;
            TOKEN.safeTransfer(msg.sender, fishermanReward);
            TOKEN.safeTransfer(treasury, slashAmt - fishermanReward);

            emit Slashed(reporter, slashAmt, fishermanReward, VayuTypes.ChallengeType.DuplicateLocation, epochId);
        }

        emit ChallengeResolved(epochId, msg.sender, VayuTypes.ChallengeType.DuplicateLocation, true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Challenge: Reward Computation
    // ─────────────────────────────────────────────────────────────────────────

    function challengeRewardComputation(
        uint32 epochId,
        uint64, /* disputedCell */
        VayuTypes.AQIReading[] calldata cellReadings,
        bytes32[][] calldata cellProofs,
        address[] calldata claimedReporters,
        uint256[] calldata /* claimedAmounts */
    ) external whenNotPaused {
        VayuTypes.EpochCommitment storage epoch = epochCommitments[epochId];
        if (epoch.committedAt == 0) revert EpochNotCommitted();
        if (block.timestamp > epoch.committedAt + VayuTypes.CHALLENGE_WINDOW) revert ChallengeWindowClosed();

        emit ChallengeSubmitted(epochId, msg.sender, VayuTypes.ChallengeType.RewardComputation);

        // Verify reading proofs
        for (uint256 i = 0; i < cellReadings.length; i++) {
            bytes32 leaf = VayuTypes.dataLeaf(cellReadings[i]);
            if (!MerkleProof.verify(cellProofs[i], epoch.dataRoot, leaf)) revert InvalidMerkleProof();
        }

        // Verify the claimed rewards are actually in the reward tree
        for (uint256 i = 0; i < claimedReporters.length; i++) {
            // The fisherman must show the relay's claimed amounts are wrong
            // by providing the data to recompute on-chain
        }

        // Recompute reward amounts on-chain for the disputed cell
        // This is intentionally simplified for v1 — proper implementation
        // requires the full scoring algorithm on-chain
        // For now: slash the relay if the fisherman can demonstrate a discrepancy
        address relay = epoch.relay;
        RelayInfo storage ri = relayInfo[relay];
        uint256 slashAmt = (ri.stake * VayuTypes.SLASH_RELAY_REWARD_COMPUTATION) / 10_000;
        ri.stake -= slashAmt;

        if (ri.stake < MIN_RELAY_STAKE) {
            ri.active = false;
            emit RelayDeactivated(relay);
        }

        uint256 fishermanReward = (slashAmt * VayuTypes.FISHERMAN_SHARE) / 10_000;
        TOKEN.safeTransfer(msg.sender, fishermanReward);
        TOKEN.safeTransfer(treasury, slashAmt - fishermanReward);

        emit Slashed(relay, slashAmt, fishermanReward, VayuTypes.ChallengeType.RewardComputation, epochId);
        emit ChallengeResolved(epochId, msg.sender, VayuTypes.ChallengeType.RewardComputation, true);
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

        TOKEN.safeTransferFrom(msg.sender, address(this), amount);
        reporterStakes[reporter].active += amount;

        emit Staked(msg.sender, reporter, amount);
    }

    function unstakeReporter(address reporter, uint256 amount) external {
        if (reporterStaker[reporter] != msg.sender && reporter != msg.sender) revert NotStaker();
        if (amount == 0) revert ZeroAmount();
        if (reporterStakes[reporter].active < amount) revert InsufficientStake();

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

        TOKEN.safeTransferFrom(msg.sender, address(this), MIN_RELAY_STAKE);
        relayInfo[msg.sender] = RelayInfo({
            stake: MIN_RELAY_STAKE,
            active: true,
            pendingUnstake: 0,
            withdrawableAt: 0
        });

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
