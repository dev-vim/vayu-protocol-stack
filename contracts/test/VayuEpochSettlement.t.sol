// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";

import {VayuToken}           from "../src/VayuToken.sol";
import {VayuRewards}         from "../src/VayuRewards.sol";
import {VayuEpochSettlement} from "../src/VayuEpochSettlement.sol";
import {VayuTypes}           from "../src/types/VayuTypes.sol";

// ─────────────────────────────────────────────────────────────────────────────
// Merkle Tree Helper
//
// Builds the simplest possible Merkle trees for test vectors:
//   - 1 leaf  : root == leaf
//   - 2 leaves: root == keccak256(sort(leaf0, leaf1))
//   - 4 leaves: two-level tree, leaves sorted pairwise bottom-up
//
// OpenZeppelin's MerkleProof.verify uses the sorted-pair convention:
//   parent = keccak256(sort(left, right))
// All trees here follow that convention so proofs round-trip correctly.
// ─────────────────────────────────────────────────────────────────────────────

library MerkleHelper {
    /// @dev Hash a sorted pair of nodes (OZ convention).
    function hashPair(bytes32 a, bytes32 b) internal pure returns (bytes32) {
        return a < b ? keccak256(abi.encodePacked(a, b))
                     : keccak256(abi.encodePacked(b, a));
    }

    /// @dev Root of a single-leaf tree (trivial).
    function root1(bytes32 leaf0) internal pure returns (bytes32) {
        return leaf0;
    }

    /// @dev Root of a 2-leaf tree.
    function root2(bytes32 leaf0, bytes32 leaf1) internal pure returns (bytes32) {
        return hashPair(leaf0, leaf1);
    }

    /// @dev Root of a 4-leaf tree (balanced).
    function root4(
        bytes32 l0, bytes32 l1, bytes32 l2, bytes32 l3
    ) internal pure returns (bytes32) {
        return hashPair(hashPair(l0, l1), hashPair(l2, l3));
    }

    // ── Proof builders ────────────────────────────────────────────────────────

    /// @dev Proof for leaf0 in a 2-leaf tree: sibling is leaf1.
    function proof2_leaf0(bytes32 leaf1) internal pure returns (bytes32[] memory p) {
        p = new bytes32[](1);
        p[0] = leaf1;
    }

    /// @dev Proof for leaf1 in a 2-leaf tree: sibling is leaf0.
    function proof2_leaf1(bytes32 leaf0) internal pure returns (bytes32[] memory p) {
        p = new bytes32[](1);
        p[0] = leaf0;
    }

    // 4-leaf balanced tree structure:
    //
    //          root
    //         /    \
    //       n01    n23
    //      /   \  /   \
    //     l0   l1 l2  l3
    //
    // n01 = hashPair(l0, l1)   n23 = hashPair(l2, l3)
    // root = hashPair(n01, n23)
    //
    // All three builders below accept all four leaves with an identical
    // signature so call sites can always pass (lc0, lc1, ln0, ln1) in
    // the same order regardless of which leaf is being proven.  The
    // commented-out parameter is the leaf being proved; it is not needed
    // to reconstruct its own proof (the verifier already has it).

    /// @dev Proof for l0: walk up l0 → n01 → root.
    ///      Siblings: l1 (leaf level), n23=hashPair(l2,l3) (root level).
    function proof4_leaf0(
        bytes32 /* l0 */, bytes32 l1, bytes32 l2, bytes32 l3
    ) internal pure returns (bytes32[] memory p) {
        p = new bytes32[](2);
        p[0] = l1;               // sibling of l0  → verifier computes n01
        p[1] = hashPair(l2, l3); // sibling of n01 → verifier computes root
    }

    /// @dev Proof for l1: walk up l1 → n01 → root.
    ///      Siblings: l0 (leaf level), n23=hashPair(l2,l3) (root level).
    function proof4_leaf1(
        bytes32 l0, bytes32 /* l1 */, bytes32 l2, bytes32 l3
    ) internal pure returns (bytes32[] memory p) {
        p = new bytes32[](2);
        p[0] = l0;               // sibling of l1  → verifier computes n01
        p[1] = hashPair(l2, l3); // sibling of n01 → verifier computes root
    }

    /// @dev Proof for l2: walk up l2 → n23 → root.
    ///      Siblings: l3 (leaf level), n01=hashPair(l0,l1) (root level).
    function proof4_leaf2(
        bytes32 l0, bytes32 l1, bytes32 /* l2 */, bytes32 l3
    ) internal pure returns (bytes32[] memory p) {
        p = new bytes32[](2);
        p[0] = l3;               // sibling of l2  → verifier computes n23
        p[1] = hashPair(l0, l1); // sibling of n23 → verifier computes root
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Base — shared deployment, actors, and epoch fixture helpers
// ─────────────────────────────────────────────────────────────────────────────

contract SettlementBase is Test {
    // ── Deployed contracts ────────────────────────────────────────────────────
    VayuToken           internal token;
    VayuRewards         internal rewards;
    VayuEpochSettlement internal settlement;

    // ── Named actors ──────────────────────────────────────────────────────────
    address internal owner     = makeAddr("owner");
    address internal treasury  = makeAddr("treasury");
    address internal relay     = makeAddr("relay");
    address internal relay2    = makeAddr("relay2");
    address internal reporter  = makeAddr("reporter");
    address internal reporter2 = makeAddr("reporter2");
    address internal fisherman = makeAddr("fisherman");
    address internal staker    = makeAddr("staker");

    // ── Constants ─────────────────────────────────────────────────────────────
    uint256 internal constant MIN_RELAY  = 10_000 * 1e18;
    uint256 internal constant MIN_REPORT = 100   * 1e18;
    uint64  internal constant H3_RES8   = uint64(8) << 52;
    uint32  internal constant EPOCH1    = 1;
    uint32  internal constant EPOCH2    = 2;

    // ── Derived timing constants ───────────────────────────────────────────────
    uint32 internal constant CHALLENGE_WINDOW         = 12 hours;
    uint32 internal constant CLAIM_EXPIRY             = 90 days;
    uint32 internal constant RELAY_UNSTAKE_COOLDOWN   = 14 days;
    uint32 internal constant REPORTER_UNSTAKE_COOLDOWN = 7 days;

    // ─────────────────────────────────────────────────────────────────────────
    // setUp
    // ─────────────────────────────────────────────────────────────────────────

    function setUp() public virtual {
        vm.startPrank(owner);

        // Deploy token — owner gets treasury allocation for test funding
        address rewardsAddr = vm.computeCreateAddress(owner, vm.getNonce(owner) + 1);
        address settlementAddr = vm.computeCreateAddress(owner, vm.getNonce(owner) + 2);
        token = new VayuToken(rewardsAddr, owner, owner, owner);

        // Deploy rewards pointing at pre-computed settlement address
        rewards = new VayuRewards(address(token), settlementAddr);

        // Deploy settlement
        settlement = new VayuEpochSettlement(address(token), address(rewards), treasury);
        require(address(settlement) == settlementAddr, "address prediction failed");

        vm.stopPrank();

        // Note: VayuToken constructor already minted 60M directly to rewardsAddr

        // Give relay enough tokens to register and extra for tests
        deal(address(token), relay,     MIN_RELAY  * 3);
        deal(address(token), relay2,    MIN_RELAY  * 3);
        deal(address(token), staker,    MIN_REPORT * 10);
        deal(address(token), fisherman, 0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /// @dev Register relay and return its initial stake.
    function _registerRelay(address r) internal {
        vm.startPrank(r);
        token.approve(address(settlement), MIN_RELAY);
        settlement.registerRelay();
        vm.stopPrank();
    }

    /// @dev Stake on behalf of reporter.
    function _stakeReporter(address s, address rep, uint256 amount) internal {
        vm.startPrank(s);
        token.approve(address(settlement), amount);
        settlement.stakeFor(rep, amount);
        vm.stopPrank();
    }

    /// @dev Commit a minimal epoch (no penalty list, empty roots for simplicity).
    function _commitEpoch(address r, uint32 epochId, bytes32 dataRoot, bytes32 rewardRoot)
        internal
    {
        address[] memory emptyPenalty = new address[](0);
        vm.prank(r);
        settlement.commitEpoch(epochId, dataRoot, rewardRoot, "ipfs://test", 1, 1, emptyPenalty);
    }

    /// @dev Commit epoch with a penalty list.
    function _commitEpochWithPenalty(
        address r,
        uint32 epochId,
        bytes32 dataRoot,
        bytes32 rewardRoot,
        address[] memory penaltyList
    ) internal {
        vm.prank(r);
        settlement.commitEpoch(epochId, dataRoot, rewardRoot, "ipfs://test", 1, 1, penaltyList);
    }

    /// @dev Advance past the challenge window so claims open.
    function _pastChallengeWindow() internal {
        vm.warp(block.timestamp + CHALLENGE_WINDOW + 1);
    }

    /// @dev Advance past claim expiry.
    function _pastClaimExpiry() internal {
        vm.warp(block.timestamp + CLAIM_EXPIRY + 1);
    }

    /// @dev Build a minimal AQIReading for a given reporter / epoch / cell.
    function _reading(
        address rep,
        uint32 epochId,
        uint64 h3Index
    ) internal pure returns (VayuTypes.AQIReading memory r) {
        r.reporter  = rep;
        r.h3Index   = h3Index;
        r.epochId   = epochId;
        r.timestamp = 1_700_000_000;
        r.aqi       = 100;
        r.pm25      = 200;
    }

    /// @dev Build a reading with a specific AQI value (for spatial anomaly tests).
    function _readingAQI(
        address rep,
        uint32 epochId,
        uint64 h3Index,
        uint16 aqi
    ) internal pure returns (VayuTypes.AQIReading memory r) {
        r = _reading(rep, epochId, h3Index);
        r.aqi = aqi;
    }

    /// @dev Compute epoch budget (mirrors VayuRewards logic).
    function _epochBudget() internal view returns (uint256) {
        return rewards.EPOCH_BUDGET();
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Constructor
// ─────────────────────────────────────────────────────────────────────────────

contract VES_Constructor_Test is SettlementBase {

    function test_constructor_setsImmutables() public view {
        assertEq(address(settlement.TOKEN()),        address(token));
        assertEq(address(settlement.REWARDS_POOL()), address(rewards));
        assertEq(settlement.treasury(),              treasury);
    }

    function test_constructor_buildsDomainSeparator() public view {
        bytes32 expected = keccak256(abi.encode(
            VayuTypes.EIP712_DOMAIN_TYPEHASH,
            keccak256(bytes(VayuTypes.DOMAIN_NAME)),
            keccak256(bytes(VayuTypes.DOMAIN_VERSION)),
            block.chainid,
            address(settlement)
        ));
        assertEq(settlement.DOMAIN_SEPARATOR(), expected);
    }

    function test_constructor_ownerIsDeployer() public view {
        assertEq(settlement.owner(), owner);
    }

    function test_constructor_notPaused() public view {
        assertFalse(settlement.paused());
    }

    function test_revert_constructor_zeroToken() public {
        vm.expectRevert(VayuEpochSettlement.ZeroAddress.selector);
        new VayuEpochSettlement(address(0), address(rewards), treasury);
    }

    function test_revert_constructor_zeroRewards() public {
        vm.expectRevert(VayuEpochSettlement.ZeroAddress.selector);
        new VayuEpochSettlement(address(token), address(0), treasury);
    }

    function test_revert_constructor_zeroTreasury() public {
        vm.expectRevert(VayuEpochSettlement.ZeroAddress.selector);
        new VayuEpochSettlement(address(token), address(rewards), address(0));
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. Relay Staking
// ─────────────────────────────────────────────────────────────────────────────

contract VES_RelayStaking_Test is SettlementBase {

    // ── registerRelay ─────────────────────────────────────────────────────────

    function test_registerRelay_setsActive() public {
        _registerRelay(relay);
        assertTrue(settlement.isActiveRelay(relay));
    }

    function test_registerRelay_recordsMinStake() public {
        _registerRelay(relay);
        assertEq(settlement.relayStake(relay), MIN_RELAY);
    }

    function test_registerRelay_transfersTokens() public {
        uint256 before = token.balanceOf(relay);
        _registerRelay(relay);
        assertEq(token.balanceOf(relay), before - MIN_RELAY);
        assertEq(token.balanceOf(address(settlement)), MIN_RELAY);
    }

    function test_registerRelay_emitsEvent() public {
        vm.startPrank(relay);
        token.approve(address(settlement), MIN_RELAY);
        vm.expectEmit(true, false, false, true);
        emit VayuEpochSettlement.RelayRegistered(relay, MIN_RELAY);
        settlement.registerRelay();
        vm.stopPrank();
    }

    function test_revert_registerRelay_alreadyRegistered() public {
        _registerRelay(relay);
        vm.startPrank(relay);
        token.approve(address(settlement), MIN_RELAY);
        vm.expectRevert(VayuEpochSettlement.RelayAlreadyRegistered.selector);
        settlement.registerRelay();
        vm.stopPrank();
    }

    function test_revert_registerRelay_pendingWithdrawalExists() public {
        _registerRelay(relay);
        vm.prank(relay);
        settlement.deregisterRelay();
        // Relay has pending unstake — re-registering must revert
        vm.startPrank(relay);
        token.approve(address(settlement), MIN_RELAY);
        vm.expectRevert(VayuEpochSettlement.PendingWithdrawalExists.selector);
        settlement.registerRelay();
        vm.stopPrank();
    }

    function test_revert_registerRelay_whenPaused() public {
        vm.prank(owner);
        settlement.pause();
        vm.startPrank(relay);
        token.approve(address(settlement), MIN_RELAY);
        vm.expectRevert();
        settlement.registerRelay();
        vm.stopPrank();
    }

    // ── deregisterRelay ───────────────────────────────────────────────────────

    function test_deregisterRelay_deactivates() public {
        _registerRelay(relay);
        vm.prank(relay);
        settlement.deregisterRelay();
        assertFalse(settlement.isActiveRelay(relay));
    }

    function test_deregisterRelay_movesPendingUnstake() public {
        _registerRelay(relay);
        vm.prank(relay);
        settlement.deregisterRelay();
        (uint256 stake,, uint256 pending,) = settlement.relayInfo(relay);
        assertEq(stake, 0);
        assertEq(pending, MIN_RELAY);
    }

    function test_deregisterRelay_setsCooldown() public {
        _registerRelay(relay);
        vm.prank(relay);
        settlement.deregisterRelay();
        (,,, uint64 withdrawableAt) = settlement.relayInfo(relay);
        assertEq(withdrawableAt, block.timestamp + RELAY_UNSTAKE_COOLDOWN);
    }

    function test_deregisterRelay_emitsEvents() public {
        _registerRelay(relay);
        uint64 expectedAt = uint64(block.timestamp + RELAY_UNSTAKE_COOLDOWN);
        vm.startPrank(relay);
        vm.expectEmit(true, false, false, false);
        emit VayuEpochSettlement.RelayDeactivated(relay);
        vm.expectEmit(true, false, false, true);
        emit VayuEpochSettlement.UnstakeInitiated(relay, MIN_RELAY, expectedAt);
        settlement.deregisterRelay();
        vm.stopPrank();
    }

    function test_revert_deregisterRelay_notRegistered() public {
        vm.prank(relay);
        vm.expectRevert(VayuEpochSettlement.RelayNotRegistered.selector);
        settlement.deregisterRelay();
    }

    // ── withdrawRelay ─────────────────────────────────────────────────────────

    function test_withdrawRelay_transfersTokens() public {
        _registerRelay(relay);
        vm.prank(relay);
        settlement.deregisterRelay();
        vm.warp(block.timestamp + RELAY_UNSTAKE_COOLDOWN + 1);
        uint256 before = token.balanceOf(relay);
        vm.prank(relay);
        settlement.withdrawRelay();
        assertEq(token.balanceOf(relay), before + MIN_RELAY);
    }

    function test_withdrawRelay_clearsPending() public {
        _registerRelay(relay);
        vm.prank(relay);
        settlement.deregisterRelay();
        vm.warp(block.timestamp + RELAY_UNSTAKE_COOLDOWN + 1);
        vm.prank(relay);
        settlement.withdrawRelay();
        (,, uint256 pending,) = settlement.relayInfo(relay);
        assertEq(pending, 0);
    }

    function test_revert_withdrawRelay_noPending() public {
        _registerRelay(relay);
        vm.prank(relay);
        vm.expectRevert(VayuEpochSettlement.NoPendingWithdrawal.selector);
        settlement.withdrawRelay();
    }

    function test_revert_withdrawRelay_cooldownNotElapsed() public {
        _registerRelay(relay);
        vm.prank(relay);
        settlement.deregisterRelay();
        vm.prank(relay);
        vm.expectRevert(VayuEpochSettlement.CooldownNotElapsed.selector);
        settlement.withdrawRelay();
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Reporter Staking
// ─────────────────────────────────────────────────────────────────────────────

contract VES_ReporterStaking_Test is SettlementBase {

    // ── stakeFor ──────────────────────────────────────────────────────────────

    function test_stakeFor_updatesActiveStake() public {
        _stakeReporter(staker, reporter, MIN_REPORT);
        assertEq(settlement.reporterStake(reporter), MIN_REPORT);
    }

    function test_stakeFor_recordsStaker() public {
        _stakeReporter(staker, reporter, MIN_REPORT);
        assertEq(settlement.reporterStaker(reporter), staker);
    }

    function test_stakeFor_additionalStake_doesNotChangeStaker() public {
        _stakeReporter(staker, reporter, MIN_REPORT);
        deal(address(token), address(this), MIN_REPORT);
        vm.startPrank(address(this));
        token.approve(address(settlement), MIN_REPORT);
        settlement.stakeFor(reporter, MIN_REPORT);
        vm.stopPrank();
        // Original staker unchanged
        assertEq(settlement.reporterStaker(reporter), staker);
        assertEq(settlement.reporterStake(reporter), MIN_REPORT * 2);
    }

    function test_stakeFor_emitsEvent() public {
        vm.startPrank(staker);
        token.approve(address(settlement), MIN_REPORT);
        vm.expectEmit(true, true, false, true);
        emit VayuEpochSettlement.Staked(staker, reporter, MIN_REPORT);
        settlement.stakeFor(reporter, MIN_REPORT);
        vm.stopPrank();
    }

    function test_revert_stakeFor_zeroReporter() public {
        vm.startPrank(staker);
        token.approve(address(settlement), MIN_REPORT);
        vm.expectRevert(VayuEpochSettlement.ZeroAddress.selector);
        settlement.stakeFor(address(0), MIN_REPORT);
        vm.stopPrank();
    }

    function test_revert_stakeFor_zeroAmount() public {
        vm.prank(staker);
        vm.expectRevert(VayuEpochSettlement.ZeroAmount.selector);
        settlement.stakeFor(reporter, 0);
    }

    function test_revert_stakeFor_whenPaused() public {
        vm.prank(owner);
        settlement.pause();
        vm.startPrank(staker);
        token.approve(address(settlement), MIN_REPORT);
        vm.expectRevert();
        settlement.stakeFor(reporter, MIN_REPORT);
        vm.stopPrank();
    }

    // ── unstakeReporter ───────────────────────────────────────────────────────

    function test_unstakeReporter_movesToPending() public {
        _stakeReporter(staker, reporter, MIN_REPORT);
        vm.prank(staker);
        settlement.unstakeReporter(reporter, MIN_REPORT);
        (uint256 active, uint256 pending,) = settlement.reporterStakes(reporter);
        assertEq(active,  0);
        assertEq(pending, MIN_REPORT);
    }

    function test_unstakeReporter_setsCooldown() public {
        _stakeReporter(staker, reporter, MIN_REPORT);
        vm.prank(staker);
        settlement.unstakeReporter(reporter, MIN_REPORT);
        (,, uint64 at) = settlement.reporterStakes(reporter);
        assertEq(at, block.timestamp + REPORTER_UNSTAKE_COOLDOWN);
    }

    function test_unstakeReporter_reporterCanUnstakeSelf() public {
        deal(address(token), reporter, MIN_REPORT);
        _stakeReporter(reporter, reporter, MIN_REPORT);
        vm.prank(reporter);
        settlement.unstakeReporter(reporter, MIN_REPORT);
        (uint256 active,,) = settlement.reporterStakes(reporter);
        assertEq(active, 0);
    }

    function test_revert_unstakeReporter_notStaker() public {
        _stakeReporter(staker, reporter, MIN_REPORT);
        vm.prank(fisherman); // not the staker or reporter
        vm.expectRevert(VayuEpochSettlement.NotStaker.selector);
        settlement.unstakeReporter(reporter, MIN_REPORT);
    }

    function test_revert_unstakeReporter_insufficientStake() public {
        _stakeReporter(staker, reporter, MIN_REPORT);
        vm.prank(staker);
        vm.expectRevert(VayuEpochSettlement.InsufficientStake.selector);
        settlement.unstakeReporter(reporter, MIN_REPORT + 1);
    }

    function test_revert_unstakeReporter_zeroAmount() public {
        _stakeReporter(staker, reporter, MIN_REPORT);
        vm.prank(staker);
        vm.expectRevert(VayuEpochSettlement.ZeroAmount.selector);
        settlement.unstakeReporter(reporter, 0);
    }

    // ── withdrawReporter ──────────────────────────────────────────────────────

    function test_withdrawReporter_transfersTokens() public {
        _stakeReporter(staker, reporter, MIN_REPORT);
        vm.prank(staker);
        settlement.unstakeReporter(reporter, MIN_REPORT);
        vm.warp(block.timestamp + REPORTER_UNSTAKE_COOLDOWN + 1);
        uint256 before = token.balanceOf(staker);
        vm.prank(staker);
        settlement.withdrawReporter(reporter);
        assertEq(token.balanceOf(staker), before + MIN_REPORT);
    }

    function test_withdrawReporter_clearsPending() public {
        _stakeReporter(staker, reporter, MIN_REPORT);
        vm.prank(staker);
        settlement.unstakeReporter(reporter, MIN_REPORT);
        vm.warp(block.timestamp + REPORTER_UNSTAKE_COOLDOWN + 1);
        vm.prank(staker);
        settlement.withdrawReporter(reporter);
        (, uint256 pending,) = settlement.reporterStakes(reporter);
        assertEq(pending, 0);
    }

    function test_revert_withdrawReporter_noPending() public {
        _stakeReporter(staker, reporter, MIN_REPORT);
        vm.prank(staker);
        vm.expectRevert(VayuEpochSettlement.NoPendingWithdrawal.selector);
        settlement.withdrawReporter(reporter);
    }

    function test_revert_withdrawReporter_cooldownNotElapsed() public {
        _stakeReporter(staker, reporter, MIN_REPORT);
        vm.prank(staker);
        settlement.unstakeReporter(reporter, MIN_REPORT);
        vm.prank(staker);
        vm.expectRevert(VayuEpochSettlement.CooldownNotElapsed.selector);
        settlement.withdrawReporter(reporter);
    }

    function test_revert_withdrawReporter_notStaker() public {
        _stakeReporter(staker, reporter, MIN_REPORT);
        vm.prank(staker);
        settlement.unstakeReporter(reporter, MIN_REPORT);
        vm.warp(block.timestamp + REPORTER_UNSTAKE_COOLDOWN + 1);
        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.NotStaker.selector);
        settlement.withdrawReporter(reporter);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. commitEpoch
// ─────────────────────────────────────────────────────────────────────────────

contract VES_CommitEpoch_Test is SettlementBase {

    function setUp() public override {
        super.setUp();
        _registerRelay(relay);
    }

    function test_commitEpoch_storesCommitment() public {
        bytes32 dr = keccak256("dataRoot");
        bytes32 rr = keccak256("rewardRoot");
        _commitEpoch(relay, EPOCH1, dr, rr);

        VayuTypes.EpochCommitment memory ec = settlement.getEpochCommitment(EPOCH1);
        assertEq(ec.dataRoot,   dr);
        assertEq(ec.rewardRoot, rr);
        assertEq(ec.relay,      relay);
        assertEq(ec.committedAt, block.timestamp);
        assertFalse(ec.finalized);
        assertFalse(ec.swept);
    }

    function test_commitEpoch_relayReceivesFee() public {
        uint256 budget = _epochBudget();
        uint256 fee    = (budget * VayuTypes.RELAY_FEE_BPS) / VayuTypes.BPS_DENOMINATOR;
        uint256 before = token.balanceOf(relay);
        _commitEpoch(relay, EPOCH1, bytes32(0), bytes32(0));
        assertEq(token.balanceOf(relay), before + fee);
    }

    function test_commitEpoch_epochBalanceIsRewardBudget() public {
        uint256 budget = _epochBudget();
        uint256 fee    = (budget * VayuTypes.RELAY_FEE_BPS) / VayuTypes.BPS_DENOMINATOR;
        _commitEpoch(relay, EPOCH1, bytes32(0), bytes32(0));
        assertEq(settlement.epochBalance(EPOCH1), budget - fee);
    }

    function test_commitEpoch_emitsEvent() public {
        bytes32 dr = keccak256("dr");
        bytes32 rr = keccak256("rr");
        address[] memory empty = new address[](0);
        vm.prank(relay);
        vm.expectEmit(true, true, false, true);
        emit VayuEpochSettlement.EpochCommitted(EPOCH1, relay, dr, rr, "ipfs://test", 1, 1);
        settlement.commitEpoch(EPOCH1, dr, rr, "ipfs://test", 1, 1, empty);
    }

    function test_commitEpoch_penaltyList_slashesReporter() public {
        _stakeReporter(staker, reporter, MIN_REPORT);
        uint256 stakeBefore = settlement.reporterStake(reporter);

        address[] memory penalty = new address[](1);
        penalty[0] = reporter;
        _commitEpochWithPenalty(relay, EPOCH1, bytes32(0), bytes32(0), penalty);

        uint256 expectedSlash = (stakeBefore * VayuTypes.SLASH_REPORTER_CONSECUTIVE_ZEROS) / VayuTypes.BPS_DENOMINATOR;
        assertEq(settlement.reporterStake(reporter), stakeBefore - expectedSlash);
    }

    function test_commitEpoch_penaltyList_setsPenaltySlashed() public {
        _stakeReporter(staker, reporter, MIN_REPORT);
        address[] memory penalty = new address[](1);
        penalty[0] = reporter;
        _commitEpochWithPenalty(relay, EPOCH1, bytes32(0), bytes32(0), penalty);
        assertTrue(settlement.penaltySlashed(EPOCH1, reporter));
    }

    function test_commitEpoch_penaltyList_duplicateEntry_slashedOnce() public {
        _stakeReporter(staker, reporter, MIN_REPORT);
        uint256 stakeBefore = settlement.reporterStake(reporter);

        // reporter appears twice — should only be slashed once
        address[] memory penalty = new address[](2);
        penalty[0] = reporter;
        penalty[1] = reporter;
        _commitEpochWithPenalty(relay, EPOCH1, bytes32(0), bytes32(0), penalty);

        uint256 expectedSlash = (stakeBefore * VayuTypes.SLASH_REPORTER_CONSECUTIVE_ZEROS) / VayuTypes.BPS_DENOMINATOR;
        assertEq(settlement.reporterStake(reporter), stakeBefore - expectedSlash);
    }

    function test_commitEpoch_penaltyList_noStake_noSlash() public {
        // reporter2 has no stake — should not revert and penalty list just skips
        address[] memory penalty = new address[](1);
        penalty[0] = reporter2;
        _commitEpochWithPenalty(relay, EPOCH1, bytes32(0), bytes32(0), penalty);
        assertEq(settlement.reporterStake(reporter2), 0);
    }

    function test_revert_commitEpoch_notActiveRelay() public {
        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.NotActiveRelay.selector);
        address[] memory empty = new address[](0);
        settlement.commitEpoch(EPOCH1, bytes32(0), bytes32(0), "", 0, 0, empty);
    }

    function test_revert_commitEpoch_alreadyCommitted() public {
        _commitEpoch(relay, EPOCH1, bytes32(0), bytes32(0));
        vm.expectRevert(abi.encodeWithSelector(VayuEpochSettlement.EpochAlreadyCommitted.selector, EPOCH1));
        _commitEpoch(relay, EPOCH1, bytes32(0), bytes32(0));
    }

    function test_revert_commitEpoch_whenPaused() public {
        vm.prank(owner);
        settlement.pause();
        vm.expectRevert();
        _commitEpoch(relay, EPOCH1, bytes32(0), bytes32(0));
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. claimReward
// ─────────────────────────────────────────────────────────────────────────────

contract VES_ClaimReward_Test is SettlementBase {

    // ── fixture state for claim tests ─────────────────────────────────────────
    uint32  internal claimEpoch = EPOCH1;
    uint256 internal claimAmount;
    bytes32 internal rewardRoot;
    bytes32[] internal claimProof;

    function setUp() public override {
        super.setUp();
        _registerRelay(relay);

        // Build a 2-leaf reward tree:
        //   leaf0 = rewardLeaf(reporter,  EPOCH1, H3_RES8, budget/2)
        //   leaf1 = rewardLeaf(reporter2, EPOCH1, H3_RES8^1, budget/2)
        uint256 budget = _epochBudget();
        uint256 fee    = (budget * VayuTypes.RELAY_FEE_BPS) / VayuTypes.BPS_DENOMINATOR;
        claimAmount = (budget - fee) / 2;

        bytes32 leaf0 = VayuTypes.rewardLeaf(reporter,  EPOCH1, H3_RES8,     claimAmount);
        bytes32 leaf1 = VayuTypes.rewardLeaf(reporter2, EPOCH1, H3_RES8 ^ 1, claimAmount);
        rewardRoot = MerkleHelper.root2(leaf0, leaf1);
        claimProof = MerkleHelper.proof2_leaf0(leaf1);

        _commitEpoch(relay, EPOCH1, bytes32(0), rewardRoot);
        _pastChallengeWindow();
    }

    function test_claimReward_transfersTokens() public {
        uint256 before = token.balanceOf(reporter);
        vm.prank(reporter);
        settlement.claimReward(claimEpoch, H3_RES8, claimAmount, claimProof);
        assertEq(token.balanceOf(reporter), before + claimAmount);
    }

    function test_claimReward_decreasesEpochBalance() public {
        uint256 before = settlement.epochBalance(claimEpoch);
        vm.prank(reporter);
        settlement.claimReward(claimEpoch, H3_RES8, claimAmount, claimProof);
        assertEq(settlement.epochBalance(claimEpoch), before - claimAmount);
    }

    function test_claimReward_marksClaimed() public {
        assertFalse(settlement.isClaimed(claimEpoch, reporter, H3_RES8));
        vm.prank(reporter);
        settlement.claimReward(claimEpoch, H3_RES8, claimAmount, claimProof);
        assertTrue(settlement.isClaimed(claimEpoch, reporter, H3_RES8));
    }

    function test_claimReward_emitsEvent() public {
        vm.prank(reporter);
        vm.expectEmit(true, true, true, true);
        emit VayuEpochSettlement.RewardClaimed(claimEpoch, reporter, H3_RES8, claimAmount);
        settlement.claimReward(claimEpoch, H3_RES8, claimAmount, claimProof);
    }

    function test_revert_claimReward_epochNotCommitted() public {
        vm.prank(reporter);
        vm.expectRevert(VayuEpochSettlement.EpochNotCommitted.selector);
        settlement.claimReward(99, H3_RES8, claimAmount, claimProof);
    }

    function test_revert_claimReward_challengeWindowOpen() public {
        // Deploy a fresh epoch and try to claim before window closes
        _commitEpoch(relay, EPOCH2, bytes32(0), rewardRoot);
        vm.prank(reporter);
        vm.expectRevert(VayuEpochSettlement.ChallengeWindowOpen.selector);
        settlement.claimReward(EPOCH2, H3_RES8, claimAmount, claimProof);
    }

    function test_revert_claimReward_claimExpired() public {
        _pastClaimExpiry();
        vm.prank(reporter);
        vm.expectRevert(VayuEpochSettlement.ClaimExpired.selector);
        settlement.claimReward(claimEpoch, H3_RES8, claimAmount, claimProof);
    }

    function test_revert_claimReward_alreadyClaimed() public {
        vm.prank(reporter);
        settlement.claimReward(claimEpoch, H3_RES8, claimAmount, claimProof);
        vm.prank(reporter);
        vm.expectRevert(VayuEpochSettlement.AlreadyClaimed.selector);
        settlement.claimReward(claimEpoch, H3_RES8, claimAmount, claimProof);
    }

    function test_revert_claimReward_invalidProof() public {
        bytes32[] memory badProof = new bytes32[](1);
        badProof[0] = keccak256("wrong");
        vm.prank(reporter);
        vm.expectRevert(VayuEpochSettlement.InvalidMerkleProof.selector);
        settlement.claimReward(claimEpoch, H3_RES8, claimAmount, badProof);
    }

    function test_revert_claimReward_whenPaused() public {
        vm.prank(owner);
        settlement.pause();
        vm.prank(reporter);
        vm.expectRevert();
        settlement.claimReward(claimEpoch, H3_RES8, claimAmount, claimProof);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. sweepExpired
// ─────────────────────────────────────────────────────────────────────────────

contract VES_SweepExpired_Test is SettlementBase {

    function setUp() public override {
        super.setUp();
        _registerRelay(relay);
        _commitEpoch(relay, EPOCH1, bytes32(0), bytes32(0));
    }

    function test_sweepExpired_transfersBalanceToTreasury() public {
        uint256 balance = settlement.epochBalance(EPOCH1);
        uint256 before  = token.balanceOf(treasury);
        _pastClaimExpiry();
        settlement.sweepExpired(EPOCH1);
        assertEq(token.balanceOf(treasury), before + balance);
    }

    function test_sweepExpired_setsSweptFlag() public {
        _pastClaimExpiry();
        settlement.sweepExpired(EPOCH1);
        assertTrue(settlement.getEpochCommitment(EPOCH1).swept);
    }

    function test_sweepExpired_zeroBalance_noTransfer() public {
        // Drain the epoch balance first via claim
        uint256 budget = _epochBudget();
        uint256 fee    = (budget * VayuTypes.RELAY_FEE_BPS) / VayuTypes.BPS_DENOMINATOR;
        uint256 amount = budget - fee;

        bytes32 leaf = VayuTypes.rewardLeaf(reporter, EPOCH2, H3_RES8, amount);
        bytes32 root = MerkleHelper.root1(leaf);

        // Re-commit epoch2 with this single-leaf root
        _commitEpoch(relay, EPOCH2, bytes32(0), root);
        _pastChallengeWindow();
        bytes32[] memory proof = new bytes32[](0);
        vm.prank(reporter);
        settlement.claimReward(EPOCH2, H3_RES8, amount, proof);

        // Now sweep — epoch balance is 0
        _pastClaimExpiry();
        uint256 before = token.balanceOf(treasury);
        settlement.sweepExpired(EPOCH2);
        assertEq(token.balanceOf(treasury), before); // nothing transferred
    }

    function test_sweepExpired_emitsEvent() public {
        uint256 balance = settlement.epochBalance(EPOCH1);
        _pastClaimExpiry();
        vm.expectEmit(true, false, false, true);
        emit VayuEpochSettlement.EpochSwept(EPOCH1, balance);
        settlement.sweepExpired(EPOCH1);
    }

    function test_revert_sweepExpired_epochNotCommitted() public {
        vm.expectRevert(VayuEpochSettlement.EpochNotCommitted.selector);
        settlement.sweepExpired(99);
    }

    function test_revert_sweepExpired_epochNotExpired() public {
        vm.expectRevert(VayuEpochSettlement.EpochNotExpired.selector);
        settlement.sweepExpired(EPOCH1);
    }

    function test_revert_sweepExpired_alreadySwept() public {
        _pastClaimExpiry();
        settlement.sweepExpired(EPOCH1);
        vm.expectRevert(VayuEpochSettlement.EpochAlreadySwept.selector);
        settlement.sweepExpired(EPOCH1);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 7. Challenge: Duplicate Location
// ─────────────────────────────────────────────────────────────────────────────

contract VES_ChallengeDuplicateLocation_Test is SettlementBase {

    VayuTypes.AQIReading internal r1;
    VayuTypes.AQIReading internal r2;
    bytes32 internal dataRoot;
    bytes32[] internal proof1;
    bytes32[] internal proof2;

    function setUp() public override {
        super.setUp();
        _registerRelay(relay);
        _stakeReporter(staker, reporter, MIN_REPORT);

        // reporter submitted from two different cells in the same epoch
        r1 = _reading(reporter, EPOCH1, H3_RES8);
        r2 = _reading(reporter, EPOCH1, H3_RES8 ^ 1);

        bytes32 leaf1 = VayuTypes.dataLeaf(r1);
        bytes32 leaf2 = VayuTypes.dataLeaf(r2);
        dataRoot = MerkleHelper.root2(leaf1, leaf2);
        proof1   = MerkleHelper.proof2_leaf0(leaf2);
        proof2   = MerkleHelper.proof2_leaf1(leaf1);

        _commitEpoch(relay, EPOCH1, dataRoot, bytes32(0));
    }

    function test_challengeDuplicateLocation_slashesReporter() public {
        uint256 stakeBefore = settlement.reporterStake(reporter);
        uint256 expectedSlash = (stakeBefore * VayuTypes.SLASH_REPORTER_DUPLICATE_LOCATION) / VayuTypes.BPS_DENOMINATOR;

        vm.prank(fisherman);
        settlement.challengeDuplicateLocation(EPOCH1, r1, proof1, r2, proof2);

        assertEq(settlement.reporterStake(reporter), stakeBefore - expectedSlash);
    }

    function test_challengeDuplicateLocation_fishermanReceivesBounty() public {
        uint256 stakeBefore  = settlement.reporterStake(reporter);
        uint256 slash        = (stakeBefore * VayuTypes.SLASH_REPORTER_DUPLICATE_LOCATION) / VayuTypes.BPS_DENOMINATOR;
        uint256 bounty       = (slash * VayuTypes.FISHERMAN_SHARE) / VayuTypes.BPS_DENOMINATOR;

        uint256 before = token.balanceOf(fisherman);
        vm.prank(fisherman);
        settlement.challengeDuplicateLocation(EPOCH1, r1, proof1, r2, proof2);
        assertEq(token.balanceOf(fisherman), before + bounty);
    }

    function test_challengeDuplicateLocation_treasuryReceivesRemainder() public {
        uint256 stakeBefore  = settlement.reporterStake(reporter);
        uint256 slash        = (stakeBefore * VayuTypes.SLASH_REPORTER_DUPLICATE_LOCATION) / VayuTypes.BPS_DENOMINATOR;
        uint256 bounty       = (slash * VayuTypes.FISHERMAN_SHARE) / VayuTypes.BPS_DENOMINATOR;

        uint256 before = token.balanceOf(treasury);
        vm.prank(fisherman);
        settlement.challengeDuplicateLocation(EPOCH1, r1, proof1, r2, proof2);
        assertEq(token.balanceOf(treasury), before + (slash - bounty));
    }

    function test_challengeDuplicateLocation_emitsEvents() public {
        vm.prank(fisherman);
        vm.expectEmit(true, true, false, true);
        emit VayuEpochSettlement.ChallengeSubmitted(EPOCH1, fisherman, VayuTypes.ChallengeType.DuplicateLocation);
        vm.expectEmit(true, true, false, true);
        emit VayuEpochSettlement.ChallengeResolved(EPOCH1, fisherman, VayuTypes.ChallengeType.DuplicateLocation, true);
        settlement.challengeDuplicateLocation(EPOCH1, r1, proof1, r2, proof2);
    }

    function test_revert_challengeDuplicateLocation_sameReporter() public {
        // reading2 has a different reporter
        VayuTypes.AQIReading memory rx = r2;
        rx.reporter = reporter2;
        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.SameReporterRequired.selector);
        settlement.challengeDuplicateLocation(EPOCH1, r1, proof1, rx, proof2);
    }

    function test_revert_challengeDuplicateLocation_sameCell() public {
        // Build two readings for the same cell — same reporter, same h3Index but
        // different AQI so the leaves are distinct — and commit them to EPOCH2.
        VayuTypes.AQIReading memory ra = _reading(reporter, EPOCH2, H3_RES8);
        VayuTypes.AQIReading memory rb = _readingAQI(reporter, EPOCH2, H3_RES8, 150);
        bytes32 la = VayuTypes.dataLeaf(ra);
        bytes32 lb = VayuTypes.dataLeaf(rb);
        bytes32 r2Root = MerkleHelper.root2(la, lb);
        bytes32[] memory pa = MerkleHelper.proof2_leaf0(lb);
        bytes32[] memory pb = MerkleHelper.proof2_leaf1(la);
        _commitEpoch(relay, EPOCH2, r2Root, bytes32(0));
        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.SameCellNotAllowed.selector);
        settlement.challengeDuplicateLocation(EPOCH2, ra, pa, rb, pb);
    }

    function test_revert_challengeDuplicateLocation_epochMismatch() public {
        // Build readings with EPOCH1 embedded in their leaves, commit them in
        // EPOCH2's dataRoot, then call challengeDuplicateLocation for EPOCH2.
        // The proofs are valid but ra.epochId (EPOCH1) != epochId (EPOCH2) → EpochMismatch.
        VayuTypes.AQIReading memory ra = _reading(reporter, EPOCH1, H3_RES8);
        VayuTypes.AQIReading memory rb = _reading(reporter, EPOCH1, H3_RES8 ^ 1);
        bytes32 la = VayuTypes.dataLeaf(ra);
        bytes32 lb = VayuTypes.dataLeaf(rb);
        bytes32 r2Root = MerkleHelper.root2(la, lb);
        bytes32[] memory pa = MerkleHelper.proof2_leaf0(lb);
        bytes32[] memory pb = MerkleHelper.proof2_leaf1(la);
        _commitEpoch(relay, EPOCH2, r2Root, bytes32(0));
        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.EpochMismatch.selector);
        settlement.challengeDuplicateLocation(EPOCH2, ra, pa, rb, pb);
    }

    function test_revert_challengeDuplicateLocation_epochNotCommitted() public {
        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.EpochNotCommitted.selector);
        settlement.challengeDuplicateLocation(99, r1, proof1, r2, proof2);
    }

    function test_revert_challengeDuplicateLocation_windowClosed() public {
        vm.warp(block.timestamp + CHALLENGE_WINDOW + 1);
        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.ChallengeWindowClosed.selector);
        settlement.challengeDuplicateLocation(EPOCH1, r1, proof1, r2, proof2);
    }

    function test_revert_challengeDuplicateLocation_invalidProof() public {
        bytes32[] memory bad = new bytes32[](1);
        bad[0] = keccak256("bad");
        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.InvalidMerkleProof.selector);
        settlement.challengeDuplicateLocation(EPOCH1, r1, bad, r2, proof2);
    }

    function test_challengeDuplicateLocation_noStake_noSlash() public {
        // reporter2 has no stake — challenge should not revert, just skips slash.
        // Readings must embed EPOCH2 so epochId matches the function call.
        VayuTypes.AQIReading memory ra = _reading(reporter2, EPOCH2, H3_RES8);
        VayuTypes.AQIReading memory rb = _reading(reporter2, EPOCH2, H3_RES8 ^ 1);
        bytes32 la = VayuTypes.dataLeaf(ra);
        bytes32 lb = VayuTypes.dataLeaf(rb);
        bytes32 root = MerkleHelper.root2(la, lb);
        bytes32[] memory pa = MerkleHelper.proof2_leaf0(lb);
        bytes32[] memory pb = MerkleHelper.proof2_leaf1(la);
        _commitEpoch(relay, EPOCH2, root, bytes32(0));
        vm.prank(fisherman);
        settlement.challengeDuplicateLocation(EPOCH2, ra, pa, rb, pb);
        // No revert, fisherman gets 0 bounty
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 8. Challenge: Spatial Anomaly
// ─────────────────────────────────────────────────────────────────────────────

contract VES_ChallengeSpatialAnomaly_Test is SettlementBase {

    // ── Fixture: 2 cell readings (AQI=300) + 2 neighbour readings (AQI=50) ──
    // Difference = 250 > SPATIAL_TOLERANCE_AQI (50) → qualifies as anomaly.
    VayuTypes.AQIReading internal cell0;
    VayuTypes.AQIReading internal cell1;
    VayuTypes.AQIReading internal neigh0;
    VayuTypes.AQIReading internal neigh1;
    bytes32 internal dataRoot4;
    bytes32[][] internal cellProofs;
    bytes32[][] internal neighProofs;

    function setUp() public override {
        super.setUp();
        _registerRelay(relay);
        _stakeReporter(staker, reporter, MIN_REPORT);

        // Cell readings — deliberately anomalous AQI
        cell0  = _readingAQI(reporter,  EPOCH1, H3_RES8,       300);
        cell1  = _readingAQI(reporter2, EPOCH1, H3_RES8 ^ 1,   300);
        neigh0 = _readingAQI(reporter,  EPOCH1, H3_RES8 ^ 2,    50);
        neigh1 = _readingAQI(reporter2, EPOCH1, H3_RES8 ^ 3,    50);

        bytes32 lc0 = VayuTypes.dataLeaf(cell0);
        bytes32 lc1 = VayuTypes.dataLeaf(cell1);
        bytes32 ln0 = VayuTypes.dataLeaf(neigh0);
        bytes32 ln1 = VayuTypes.dataLeaf(neigh1);
        dataRoot4 = MerkleHelper.root4(lc0, lc1, ln0, ln1);

        cellProofs = new bytes32[][](2);
        cellProofs[0] = MerkleHelper.proof4_leaf0(lc0, lc1, ln0, ln1);
        cellProofs[1] = MerkleHelper.proof4_leaf1(lc0, lc1, ln0, ln1);

        neighProofs = new bytes32[][](2);
        neighProofs[0] = MerkleHelper.proof4_leaf2(lc0, lc1, ln0, ln1);
        neighProofs[1] = new bytes32[](2);
        neighProofs[1][0] = ln0;
        neighProofs[1][1] = MerkleHelper.hashPair(lc0, lc1);

        _commitEpoch(relay, EPOCH1, dataRoot4, bytes32(0));
    }

    function test_challengeSpatialAnomaly_slashesReporter() public {
        uint256 stakeBefore = settlement.reporterStake(reporter);
        uint256 expectedSlash = (stakeBefore * VayuTypes.SLASH_REPORTER_FISHERMAN) / VayuTypes.BPS_DENOMINATOR;

        VayuTypes.AQIReading[] memory cells  = new VayuTypes.AQIReading[](1);
        cells[0] = cell0;
        VayuTypes.AQIReading[] memory neighs = new VayuTypes.AQIReading[](2);
        neighs[0] = neigh0;
        neighs[1] = neigh1;

        bytes32[][] memory cp = new bytes32[][](1);
        cp[0] = cellProofs[0];
        bytes32[][] memory np = new bytes32[][](2);
        np[0] = neighProofs[0];
        np[1] = neighProofs[1];

        vm.prank(fisherman);
        settlement.challengeSpatialAnomaly(EPOCH1, H3_RES8, cells, cp, neighs, np);

        assertEq(settlement.reporterStake(reporter), stakeBefore - expectedSlash);
    }

    function test_revert_challengeSpatialAnomaly_emptyArray() public {
        VayuTypes.AQIReading[] memory empty = new VayuTypes.AQIReading[](0);
        VayuTypes.AQIReading[] memory cells  = new VayuTypes.AQIReading[](1);
        cells[0] = cell0;
        bytes32[][] memory emptyProofs = new bytes32[][](0);
        bytes32[][] memory cp = new bytes32[][](1);
        cp[0] = cellProofs[0];

        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.EmptyArray.selector);
        settlement.challengeSpatialAnomaly(EPOCH1, H3_RES8, empty, emptyProofs, cells, cp);
    }

    function test_revert_challengeSpatialAnomaly_notAnomaly() public {
        // AQI difference within tolerance — not a valid anomaly
        VayuTypes.AQIReading memory nonAnomCell  = _readingAQI(reporter, EPOCH1, H3_RES8,     100);
        VayuTypes.AQIReading memory nonAnomNeigh = _readingAQI(reporter, EPOCH1, H3_RES8 ^ 2, 110);

        bytes32 lc = VayuTypes.dataLeaf(nonAnomCell);
        bytes32 ln = VayuTypes.dataLeaf(nonAnomNeigh);
        bytes32 root = MerkleHelper.root2(lc, ln);
        _commitEpoch(relay, EPOCH2, root, bytes32(0));

        VayuTypes.AQIReading[] memory cells  = new VayuTypes.AQIReading[](1);
        cells[0] = nonAnomCell;
        VayuTypes.AQIReading[] memory neighs = new VayuTypes.AQIReading[](1);
        neighs[0] = nonAnomNeigh;
        bytes32[][] memory cp = new bytes32[][](1);
        cp[0] = MerkleHelper.proof2_leaf0(ln);
        bytes32[][] memory np = new bytes32[][](1);
        np[0] = MerkleHelper.proof2_leaf1(lc);

        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.NotAnomaly.selector);
        settlement.challengeSpatialAnomaly(EPOCH2, H3_RES8, cells, cp, neighs, np);
    }

    function test_revert_challengeSpatialAnomaly_windowClosed() public {
        vm.warp(block.timestamp + CHALLENGE_WINDOW + 1);
        VayuTypes.AQIReading[] memory cells  = new VayuTypes.AQIReading[](1);
        cells[0] = cell0;
        VayuTypes.AQIReading[] memory neighs = new VayuTypes.AQIReading[](1);
        neighs[0] = neigh0;
        bytes32[][] memory cp = new bytes32[][](1);
        cp[0] = cellProofs[0];
        bytes32[][] memory np = new bytes32[][](1);
        np[0] = neighProofs[0];
        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.ChallengeWindowClosed.selector);
        settlement.challengeSpatialAnomaly(EPOCH1, H3_RES8, cells, cp, neighs, np);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 9. Challenge: Reward Computation
// ─────────────────────────────────────────────────────────────────────────────

contract VES_ChallengeRewardComputation_Test is SettlementBase {

    VayuTypes.AQIReading internal r1;
    bytes32 internal dataRoot;
    bytes32[][] internal cellProofs;
    address[] internal claimedReporters;
    uint256[] internal claimedAmounts;

    function setUp() public override {
        super.setUp();
        _registerRelay(relay);

        r1 = _reading(reporter, EPOCH1, H3_RES8);
        bytes32 leaf = VayuTypes.dataLeaf(r1);
        dataRoot = MerkleHelper.root1(leaf);

        _commitEpoch(relay, EPOCH1, dataRoot, bytes32(0));

        cellProofs = new bytes32[][](1);
        cellProofs[0] = new bytes32[](0); // single-leaf proof is empty

        claimedReporters = new address[](1);
        claimedReporters[0] = reporter;
        claimedAmounts = new uint256[](1);
        claimedAmounts[0] = 1000;
    }

    function test_challengeRewardComputation_slashesRelay() public {
        uint256 stakeBefore = settlement.relayStake(relay);
        uint256 expectedSlash = (stakeBefore * VayuTypes.SLASH_RELAY_REWARD_COMPUTATION) / VayuTypes.BPS_DENOMINATOR;

        VayuTypes.AQIReading[] memory cells = new VayuTypes.AQIReading[](1);
        cells[0] = r1;

        vm.prank(fisherman);
        settlement.challengeRewardComputation(EPOCH1, H3_RES8, cells, cellProofs, claimedReporters, claimedAmounts);

        assertEq(settlement.relayStake(relay), stakeBefore - expectedSlash);
    }

    function test_challengeRewardComputation_fishermanReceivesBounty() public {
        uint256 stakeBefore = settlement.relayStake(relay);
        uint256 slash  = (stakeBefore * VayuTypes.SLASH_RELAY_REWARD_COMPUTATION) / VayuTypes.BPS_DENOMINATOR;
        uint256 bounty = (slash * VayuTypes.FISHERMAN_SHARE) / VayuTypes.BPS_DENOMINATOR;

        VayuTypes.AQIReading[] memory cells = new VayuTypes.AQIReading[](1);
        cells[0] = r1;

        uint256 before = token.balanceOf(fisherman);
        vm.prank(fisherman);
        settlement.challengeRewardComputation(EPOCH1, H3_RES8, cells, cellProofs, claimedReporters, claimedAmounts);
        assertEq(token.balanceOf(fisherman), before + bounty);
    }

    function test_challengeRewardComputation_deactivatesRelay_whenBelowMin() public {
        // Use a relay with just-above minimum stake, slash 30% → falls below
        VayuTypes.AQIReading[] memory cells = new VayuTypes.AQIReading[](1);
        cells[0] = r1;

        vm.prank(fisherman);
        settlement.challengeRewardComputation(EPOCH1, H3_RES8, cells, cellProofs, claimedReporters, claimedAmounts);

        // MIN_RELAY = 10_000e18, slash 30% = 3_000e18 → remaining 7_000e18 < MIN_RELAY
        assertFalse(settlement.isActiveRelay(relay));
    }

    function test_revert_challengeRewardComputation_windowClosed() public {
        vm.warp(block.timestamp + CHALLENGE_WINDOW + 1);
        VayuTypes.AQIReading[] memory cells = new VayuTypes.AQIReading[](1);
        cells[0] = r1;
        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.ChallengeWindowClosed.selector);
        settlement.challengeRewardComputation(EPOCH1, H3_RES8, cells, cellProofs, claimedReporters, claimedAmounts);
    }

    function test_revert_challengeRewardComputation_invalidProof() public {
        bytes32[][] memory bad = new bytes32[][](1);
        bad[0] = new bytes32[](1);
        bad[0][0] = keccak256("bad");

        VayuTypes.AQIReading[] memory cells = new VayuTypes.AQIReading[](1);
        cells[0] = r1;

        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.InvalidMerkleProof.selector);
        settlement.challengeRewardComputation(EPOCH1, H3_RES8, cells, bad, claimedReporters, claimedAmounts);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 10. Challenge: Penalty List Fraud
// ─────────────────────────────────────────────────────────────────────────────

contract VES_ChallengePenaltyList_Test is SettlementBase {

    // We need:
    //   penaltyEpochId = EPOCH2 (the epoch where reporter was penalized)
    //   proofEpochId   = EPOCH1 (an epoch within the lookback window where
    //                            the reporter had a valid reading)
    uint32 internal constant PROOF_EPOCH   = 1;
    uint32 internal constant PENALTY_EPOCH = 5; // within threshold=10 lookback from epoch 5

    VayuTypes.AQIReading internal proofReading;
    bytes32[] internal merkleProof;

    function setUp() public override {
        super.setUp();
        _registerRelay(relay);
        _stakeReporter(staker, reporter, MIN_REPORT);

        // Commit proofEpoch with a reading from reporter
        proofReading = _reading(reporter, PROOF_EPOCH, H3_RES8);
        bytes32 leaf = VayuTypes.dataLeaf(proofReading);
        bytes32 root = MerkleHelper.root1(leaf);
        _commitEpoch(relay, PROOF_EPOCH, root, bytes32(0));

        merkleProof = new bytes32[](0); // single-leaf proof is empty

        // Commit penaltyEpoch with reporter on the penalty list
        address[] memory penalty = new address[](1);
        penalty[0] = reporter;
        _commitEpochWithPenalty(relay, PENALTY_EPOCH, bytes32(0), bytes32(0), penalty);
    }

    function test_challengePenaltyList_clearsPenaltySlashed() public {
        assertTrue(settlement.penaltySlashed(PENALTY_EPOCH, reporter));
        vm.prank(fisherman);
        settlement.challengePenaltyList(PENALTY_EPOCH, reporter, PROOF_EPOCH, proofReading, merkleProof);
        assertFalse(settlement.penaltySlashed(PENALTY_EPOCH, reporter));
    }

    function test_challengePenaltyList_slashesRelay() public {
        uint256 stakeBefore = settlement.relayStake(relay);
        uint256 expectedSlash = (stakeBefore * VayuTypes.SLASH_RELAY_PENALTY_LIST) / VayuTypes.BPS_DENOMINATOR;

        vm.prank(fisherman);
        settlement.challengePenaltyList(PENALTY_EPOCH, reporter, PROOF_EPOCH, proofReading, merkleProof);

        assertEq(settlement.relayStake(relay), stakeBefore - expectedSlash);
    }

    function test_challengePenaltyList_fishermanReceivesBounty() public {
        uint256 stakeBefore = settlement.relayStake(relay);
        uint256 slash  = (stakeBefore * VayuTypes.SLASH_RELAY_PENALTY_LIST) / VayuTypes.BPS_DENOMINATOR;
        uint256 bounty = (slash * VayuTypes.FISHERMAN_SHARE) / VayuTypes.BPS_DENOMINATOR;

        uint256 before = token.balanceOf(fisherman);
        vm.prank(fisherman);
        settlement.challengePenaltyList(PENALTY_EPOCH, reporter, PROOF_EPOCH, proofReading, merkleProof);
        assertEq(token.balanceOf(fisherman), before + bounty);
    }

    function test_challengePenaltyList_deactivatesRelay_whenBelowMin() public {
        vm.prank(fisherman);
        settlement.challengePenaltyList(PENALTY_EPOCH, reporter, PROOF_EPOCH, proofReading, merkleProof);
        // 10_000e18 - 30% = 7_000e18 < MIN_RELAY
        assertFalse(settlement.isActiveRelay(relay));
    }

    function test_challengePenaltyList_emitsEvents() public {
        vm.prank(fisherman);
        vm.expectEmit(true, true, false, true);
        emit VayuEpochSettlement.ChallengeSubmitted(PENALTY_EPOCH, fisherman, VayuTypes.ChallengeType.PenaltyListFraud);
        vm.expectEmit(true, true, false, true);
        emit VayuEpochSettlement.ChallengeResolved(PENALTY_EPOCH, fisherman, VayuTypes.ChallengeType.PenaltyListFraud, true);
        settlement.challengePenaltyList(PENALTY_EPOCH, reporter, PROOF_EPOCH, proofReading, merkleProof);
    }

    function test_revert_challengePenaltyList_reporterMismatch() public {
        VayuTypes.AQIReading memory wrong = proofReading;
        wrong.reporter = reporter2;
        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.ReporterMismatch.selector);
        settlement.challengePenaltyList(PENALTY_EPOCH, reporter, PROOF_EPOCH, wrong, merkleProof);
    }

    function test_revert_challengePenaltyList_epochMismatch() public {
        VayuTypes.AQIReading memory wrong = proofReading;
        wrong.epochId = PROOF_EPOCH + 1; // reading epochId doesn't match proofEpochId
        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.EpochMismatch.selector);
        settlement.challengePenaltyList(PENALTY_EPOCH, reporter, PROOF_EPOCH, wrong, merkleProof);
    }

    function test_revert_challengePenaltyList_proofEpochOutOfRange_tooEarly() public {
        // proofEpochId must be > windowStart = penaltyEpochId - threshold = 5 - 10 = 0
        // epoch 0 is exactly at or before windowStart.
        // proofReading.epochId must equal proofEpochId (0) so the epoch-mismatch
        // check passes and the range check fires instead.
        VayuTypes.AQIReading memory pr0 = _reading(reporter, 0, H3_RES8);
        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.ProofEpochOutOfRange.selector);
        settlement.challengePenaltyList(PENALTY_EPOCH, reporter, 0, pr0, merkleProof);
    }

    function test_revert_challengePenaltyList_proofEpochOutOfRange_tooLate() public {
        // proofEpochId must be <= penaltyEpochId.
        // proofReading.epochId must equal proofEpochId so the epoch-mismatch
        // check passes and the range check fires.
        VayuTypes.AQIReading memory prLate = _reading(reporter, PENALTY_EPOCH + 1, H3_RES8);
        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.ProofEpochOutOfRange.selector);
        settlement.challengePenaltyList(PENALTY_EPOCH, reporter, PENALTY_EPOCH + 1, prLate, merkleProof);
    }

    function test_revert_challengePenaltyList_reporterNotPenalized() public {
        // reporter2 was never on the penalty list.
        // proofReading.reporter must equal reporter2 so the reporter-mismatch check
        // passes and the not-penalized check fires.
        VayuTypes.AQIReading memory pr2 = _reading(reporter2, PROOF_EPOCH, H3_RES8);
        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.ReporterNotPenalized.selector);
        settlement.challengePenaltyList(PENALTY_EPOCH, reporter2, PROOF_EPOCH, pr2, merkleProof);
    }

    function test_revert_challengePenaltyList_proofEpochNotCommitted() public {
        // proofEpochId=3 is in range (0 < 3 <= 5) but epoch 3 was never committed.
        // proofReading.epochId must equal 3 so the epoch-mismatch check passes
        // and the not-committed check fires.
        VayuTypes.AQIReading memory pr3 = _reading(reporter, 3, H3_RES8);
        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.EpochNotCommitted.selector);
        settlement.challengePenaltyList(PENALTY_EPOCH, reporter, 3, pr3, merkleProof);
    }

    function test_revert_challengePenaltyList_windowClosed() public {
        vm.warp(block.timestamp + CHALLENGE_WINDOW + 1);
        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.ChallengeWindowClosed.selector);
        settlement.challengePenaltyList(PENALTY_EPOCH, reporter, PROOF_EPOCH, proofReading, merkleProof);
    }

    function test_revert_challengePenaltyList_invalidProof() public {
        bytes32[] memory bad = new bytes32[](1);
        bad[0] = keccak256("bad");
        vm.prank(fisherman);
        vm.expectRevert(VayuEpochSettlement.InvalidMerkleProof.selector);
        settlement.challengePenaltyList(PENALTY_EPOCH, reporter, PROOF_EPOCH, proofReading, bad);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 11. Admin
// ─────────────────────────────────────────────────────────────────────────────

contract VES_Admin_Test is SettlementBase {

    function test_setTreasury_updatesAddress() public {
        address newTreasury = makeAddr("newTreasury");
        vm.prank(owner);
        settlement.setTreasury(newTreasury);
        assertEq(settlement.treasury(), newTreasury);
    }

    function test_setTreasury_emitsEvent() public {
        address newTreasury = makeAddr("newTreasury");
        vm.prank(owner);
        vm.expectEmit(true, true, false, false);
        emit VayuEpochSettlement.TreasuryUpdated(treasury, newTreasury);
        settlement.setTreasury(newTreasury);
    }

    function test_revert_setTreasury_zeroAddress() public {
        vm.prank(owner);
        vm.expectRevert(VayuEpochSettlement.ZeroAddress.selector);
        settlement.setTreasury(address(0));
    }

    function test_revert_setTreasury_notOwner() public {
        vm.prank(fisherman);
        vm.expectRevert();
        settlement.setTreasury(makeAddr("x"));
    }

    function test_pause_pausesContract() public {
        vm.prank(owner);
        settlement.pause();
        assertTrue(settlement.paused());
    }

    function test_unpause_unpausesContract() public {
        vm.prank(owner);
        settlement.pause();
        vm.prank(owner);
        settlement.unpause();
        assertFalse(settlement.paused());
    }

    function test_revert_pause_notOwner() public {
        vm.prank(fisherman);
        vm.expectRevert();
        settlement.pause();
    }

    function test_revert_unpause_notOwner() public {
        vm.prank(owner);
        settlement.pause();
        vm.prank(fisherman);
        vm.expectRevert();
        settlement.unpause();
    }
}
