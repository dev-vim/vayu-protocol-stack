// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {StdInvariant} from "forge-std/StdInvariant.sol";

import {VayuToken}           from "../src/VayuToken.sol";
import {VayuRewards}         from "../src/VayuRewards.sol";
import {VayuEpochSettlement} from "../src/VayuEpochSettlement.sol";
import {VayuTypes}           from "../src/types/VayuTypes.sol";

contract VayuTokenHandler is Test {
    VayuToken public token;
    address public treasury;

    constructor(VayuToken _token, address _treasury) {
        token = _token;
        treasury = _treasury;
    }

    function transferFromTreasury(uint256 amount, address to) external {
        if (to == address(0)) to = address(0xBEEF);

        uint256 bal = token.balanceOf(treasury);
        amount = bound(amount, 0, bal);

        vm.prank(treasury);
        // forge-lint: disable-next-item(erc20-unchecked-transfer) — OZ ERC-20 reverts on failure
        token.transfer(to, amount);
    }

    function approveAndTransferFromTreasury(
        uint256 approveAmount,
        uint256 spendAmount,
        address spender,
        address to
    ) external {
        if (spender == address(0)) spender = address(0xCAFE);
        if (to == address(0)) to = address(0xBEEF);

        uint256 bal = token.balanceOf(treasury);
        approveAmount = bound(approveAmount, 0, bal);
        spendAmount = bound(spendAmount, 0, approveAmount);

        vm.prank(treasury);
        token.approve(spender, approveAmount);

        vm.prank(spender);
        // forge-lint: disable-next-item(erc20-unchecked-transfer) — OZ ERC-20 reverts on failure
        token.transferFrom(treasury, to, spendAmount);
    }
}

contract VayuRewardsHandler is Test {
    VayuRewards public rewards;
    address public settlement;

    constructor(VayuRewards _rewards, address _settlement) {
        rewards = _rewards;
        settlement = _settlement;
    }

    function releaseAsSettlement(uint32 epochId) external {
        vm.prank(settlement);
        try rewards.releaseEpochBudget(epochId) {
        } catch {
        }
    }

    function releaseAsOther(address caller, uint32 epochId) external {
        if (caller == settlement) caller = address(0xD00D);
        vm.prank(caller);
        try rewards.releaseEpochBudget(epochId) {
        } catch {
        }
    }
}

contract VayuTokenInvariantTest is StdInvariant, Test {
    VayuToken public token;
    VayuTokenHandler public handler;

    address public rewardsPool = makeAddr("rewardsPool");
    address public treasury = makeAddr("treasury");
    address public team = makeAddr("team");
    address public community = makeAddr("community");

    function setUp() public {
        token = new VayuToken(rewardsPool, treasury, team, community);
        handler = new VayuTokenHandler(token, treasury);

        targetContract(address(handler));
    }

    function invariant_totalSupplyConstant() public view {
        assertEq(token.totalSupply(), token.TOTAL_SUPPLY());
    }

    function invariant_immutablesUnchanged() public view {
        assertEq(token.REWARDS_POOL(), rewardsPool);
        assertEq(token.TREASURY(), treasury);
    }
}

contract VayuRewardsInvariantTest is StdInvariant, Test {
    VayuToken public token;
    VayuRewards public rewards;
    VayuRewardsHandler public handler;

    address public settlement = makeAddr("settlement");
    address public treasury = makeAddr("treasury");
    address public team = makeAddr("team");
    address public community = makeAddr("community");

    uint256 public constant POOL_ALLOCATION = 60_000_000 * 1e18;

    function setUp() public {
        token = new VayuToken(makeAddr("dummyRewards"), treasury, team, community);
        rewards = new VayuRewards(address(token), settlement);
        deal(address(token), address(rewards), POOL_ALLOCATION);

        handler = new VayuRewardsHandler(rewards, settlement);
        targetContract(address(handler));
    }

    function invariant_poolConservation() public view {
        uint256 pool = token.balanceOf(address(rewards));
        uint256 released = token.balanceOf(settlement);
        assertEq(pool + released, POOL_ALLOCATION);
    }

    function invariant_neverOverRelease() public view {
        assertLe(token.balanceOf(settlement), POOL_ALLOCATION);
        assertLe(rewards.epochsReleased(), rewards.TOTAL_EPOCHS() + 1);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// VayuEpochSettlement Invariant Tests
//
// The handler drives the settlement through the full lifecycle — relay and
// reporter staking, epoch commits, and sweeps — without constructing Merkle
// proofs so it stays compact while still exercising the core accounting paths.
//
// Invariants:
//   I1 – Solvency          : balance == all reporter stakes + relay stakes + epoch balances
//   I2 – Active relay min  : active relay stake is always >= MIN_RELAY_STAKE
//   I3 – Exclusive state   : an active relay never carries a pending unstake
//   I4 – Swept zero balance: a swept epoch always has epochBalance == 0
//   I5 – Domain separator  : DOMAIN_SEPARATOR is immutable after deployment
//   I6 – Treasury non-zero : treasury is never set to address(0)
//   I7 – Immutables        : TOKEN and REWARDS_POOL addresses never change
// ─────────────────────────────────────────────────────────────────────────────

/// @dev Drives VayuEpochSettlement through all main state-mutating paths.
///      A fixed, small actor set keeps the search space tight while still
///      exercising both relay and reporter accounting fully.
contract VayuSettlementHandler is Test {
    VayuEpochSettlement public settlement;
    VayuToken           public token;

    uint256 internal constant MIN_RELAY  = 10_000 * 1e18;
    uint256 internal constant MIN_REPORT = 100    * 1e18;

    // Two relays and three reporter/staker pairs.
    address[2] internal _relays;
    address[3] internal _reporters;
    address[3] internal _stakers;   // _stakers[i] staked for _reporters[i]

    // Epoch tracking — only epochs that were successfully committed.
    uint32   internal _nextEpoch = 1;
    uint32[] internal _committedEpochs;

    constructor(VayuEpochSettlement _settlement, VayuToken _token) {
        settlement = _settlement;
        token      = _token;

        _relays[0]    = makeAddr("sh_relay0");
        _relays[1]    = makeAddr("sh_relay1");
        _reporters[0] = makeAddr("sh_reporter0");
        _reporters[1] = makeAddr("sh_reporter1");
        _reporters[2] = makeAddr("sh_reporter2");
        _stakers[0]   = makeAddr("sh_staker0");
        _stakers[1]   = makeAddr("sh_staker1");
        _stakers[2]   = makeAddr("sh_staker2");

        for (uint256 i; i < 3; i++) {
            deal(address(token), _stakers[i], MIN_REPORT * 100);
        }
        for (uint256 i; i < 2; i++) {
            deal(address(token), _relays[i], MIN_RELAY * 10);
        }
    }

    // ── Relay lifecycle ───────────────────────────────────────────────────────

    function registerRelay(uint8 seed) external {
        address r = _relays[seed % 2];
        (uint256 existing, bool active, uint256 pending,) = settlement.relayInfo(r);
        if (active || pending > 0) return;
        // Top up the relay's wallet so it can always cover the required deposit.
        uint256 topUp = existing < MIN_RELAY ? MIN_RELAY - existing : 0;
        if (topUp > 0) deal(address(token), r, token.balanceOf(r) + topUp);
        vm.startPrank(r);
        token.approve(address(settlement), topUp);
        try settlement.registerRelay() {} catch {}
        vm.stopPrank();
    }

    function deregisterRelay(uint8 seed) external {
        address r = _relays[seed % 2];
        (, bool active,,) = settlement.relayInfo(r);
        if (!active) return;
        vm.prank(r);
        try settlement.deregisterRelay() {} catch {}
    }

    function withdrawRelay(uint8 seed) external {
        address r = _relays[seed % 2];
        (,, uint256 pending, uint64 at) = settlement.relayInfo(r);
        if (pending == 0) return;
        if (block.timestamp < at) vm.warp(at);
        vm.prank(r);
        try settlement.withdrawRelay() {} catch {}
    }

    // ── Reporter staking ──────────────────────────────────────────────────────

    function stakeReporter(uint8 seed, uint256 amount) external {
        uint256 idx    = seed % 3;
        address rep    = _reporters[idx];
        address staker = _stakers[idx];
        amount = bound(amount, 1e18, MIN_REPORT * 5);
        deal(address(token), staker, token.balanceOf(staker) + amount);
        vm.startPrank(staker);
        token.approve(address(settlement), amount);
        try settlement.stakeFor(rep, amount) {} catch {}
        vm.stopPrank();
    }

    function unstakeReporter(uint8 seed, uint256 amount) external {
        uint256 idx    = seed % 3;
        address rep    = _reporters[idx];
        address staker = _stakers[idx];
        uint256 active = settlement.reporterStake(rep);
        if (active == 0) return;
        amount = bound(amount, 1, active);
        vm.prank(staker);
        try settlement.unstakeReporter(rep, amount) {} catch {}
    }

    function withdrawReporter(uint8 seed) external {
        uint256 idx    = seed % 3;
        address rep    = _reporters[idx];
        address staker = _stakers[idx];
        (, uint256 pending, uint64 at) = settlement.reporterStakes(rep);
        if (pending == 0) return;
        if (block.timestamp < at) vm.warp(at);
        vm.prank(staker);
        try settlement.withdrawReporter(rep) {} catch {}
    }

    // ── Epoch lifecycle ───────────────────────────────────────────────────────

    /// @dev Commits with an empty reward root so no claims are possible. The full
    ///      epoch balance accumulates in the contract until the epoch is swept.
    function commitEpoch(uint8 relaySeed) external {
        address r = _relays[relaySeed % 2];
        (, bool active,,) = settlement.relayInfo(r);
        if (!active) return;
        uint32 epochId = _nextEpoch;
        address[] memory empty = new address[](0);
        vm.prank(r);
        try settlement.commitEpoch(epochId, bytes32(0), bytes32(0), "", 0, 0, empty) {
            _committedEpochs.push(epochId);
            _nextEpoch++;
        } catch {}
    }

    /// @dev Sweeps a randomly chosen committed epoch, warping past the 90-day
    ///      claim expiry if necessary.
    function sweepExpired(uint8 seed) external {
        if (_committedEpochs.length == 0) return;
        uint32 epochId = _committedEpochs[seed % _committedEpochs.length];
        VayuTypes.EpochCommitment memory ec = settlement.getEpochCommitment(epochId);
        if (ec.swept) return;
        uint64 expiry = ec.committedAt + 90 days + 1;
        if (block.timestamp < expiry) vm.warp(expiry);
        try settlement.sweepExpired(epochId) {} catch {}
    }

    // ── View helpers for invariant assertions ─────────────────────────────────

    function getRelays()          external view returns (address[2] memory) { return _relays; }
    function getReporters()       external view returns (address[3] memory) { return _reporters; }
    function getCommittedEpochs() external view returns (uint32[]   memory) { return _committedEpochs; }
}

contract VayuSettlementInvariantTest is StdInvariant, Test {
    VayuToken             public token;
    VayuRewards           public rewards;
    VayuEpochSettlement   public settlement;
    VayuSettlementHandler public handler;

    address internal _treasury = makeAddr("sit_treasury");
    address internal _owner    = makeAddr("sit_owner");

    bytes32 internal _initialDomainSeparator;

    function setUp() public {
        vm.startPrank(_owner);

        // Pre-compute future addresses to satisfy the circular dependency:
        // VayuToken needs the rewards address, VayuRewards needs the settlement
        // address, and both are only known after deployment.
        address rewardsAddr    = vm.computeCreateAddress(_owner, vm.getNonce(_owner) + 1);
        address settlementAddr = vm.computeCreateAddress(_owner, vm.getNonce(_owner) + 2);

        token      = new VayuToken(rewardsAddr, _owner, _owner, _owner);
        rewards    = new VayuRewards(address(token), settlementAddr);
        settlement = new VayuEpochSettlement(address(token), address(rewards), _treasury);

        require(address(settlement) == settlementAddr, "address prediction failed");
        vm.stopPrank();

        // Capture the immutable domain separator for I5.
        _initialDomainSeparator = settlement.DOMAIN_SEPARATOR();

        handler = new VayuSettlementHandler(settlement, token);
        targetContract(address(handler));
    }

    // ── I1: Solvency ──────────────────────────────────────────────────────────
    //
    // The settlement contract is solvent when its token balance equals the sum
    // of every obligation it holds:
    //
    //   • reporter active stakes  — locked collateral backing active reporters
    //   • reporter pending stakes — unstaked but not yet withdrawn
    //   • relay active stakes     — locked collateral backing active relays
    //   • relay pending unstakes  — deregistered but not yet withdrawn
    //   • epoch reward balances   — unclaimed/unswept reward budgets
    //
    // Every function that moves tokens in or out of the contract has a
    // corresponding bookkeeping update that keeps this equality exact.
    function invariant_solvency() public view {
        uint256 obligated;

        address[3] memory reporters = handler.getReporters();
        for (uint256 i; i < reporters.length; i++) {
            (uint256 active, uint256 pending,) = settlement.reporterStakes(reporters[i]);
            obligated += active + pending;
        }

        address[2] memory relays = handler.getRelays();
        for (uint256 i; i < relays.length; i++) {
            (uint256 stake,, uint256 pending,) = settlement.relayInfo(relays[i]);
            obligated += stake + pending;
        }

        uint32[] memory epochs = handler.getCommittedEpochs();
        for (uint256 i; i < epochs.length; i++) {
            obligated += settlement.epochBalance(epochs[i]);
        }

        assertEq(token.balanceOf(address(settlement)), obligated);
    }

    // ── I2: Active relay minimum stake ────────────────────────────────────────
    //
    // The only operation that reduces a relay's stake below MIN_RELAY_STAKE is
    // a challenge slash, which immediately sets active = false. Therefore any
    // relay still flagged as active must hold at least MIN_RELAY_STAKE.
    function invariant_activeRelayHasMinStake() public view {
        address[2] memory relays = handler.getRelays();
        for (uint256 i; i < relays.length; i++) {
            (uint256 stake, bool active,,) = settlement.relayInfo(relays[i]);
            if (active) assertGe(stake, settlement.MIN_RELAY_STAKE());
        }
    }

    // ── I3: Exclusive relay state ──────────────────────────────────────────────
    //
    // active = true and pendingUnstake > 0 are mutually exclusive states.
    // deregisterRelay atomically sets active = false and moves stake to pending;
    // registerRelay requires pendingUnstake == 0 before re-activating.
    function invariant_activeRelayNoPendingUnstake() public view {
        address[2] memory relays = handler.getRelays();
        for (uint256 i; i < relays.length; i++) {
            (, bool active, uint256 pending,) = settlement.relayInfo(relays[i]);
            if (active) assertEq(pending, 0);
        }
    }

    // ── I4: Swept epoch zero balance ───────────────────────────────────────────
    //
    // sweepExpired zeroes epochBalance before setting swept = true. Once swept,
    // the flag cannot be cleared, so the balance must remain zero forever after.
    function invariant_sweptEpochHasZeroBalance() public view {
        uint32[] memory epochs = handler.getCommittedEpochs();
        for (uint256 i; i < epochs.length; i++) {
            VayuTypes.EpochCommitment memory ec = settlement.getEpochCommitment(epochs[i]);
            if (ec.swept) assertEq(settlement.epochBalance(epochs[i]), 0);
        }
    }

    // ── I5: Domain separator immutability ─────────────────────────────────────
    //
    // DOMAIN_SEPARATOR is an immutable computed once in the constructor from
    // the chain id and contract address. No setter exists.
    function invariant_domainSeparatorImmutable() public view {
        assertEq(settlement.DOMAIN_SEPARATOR(), _initialDomainSeparator);
    }

    // ── I6: Treasury address is never zero ────────────────────────────────────
    //
    // setTreasury explicitly rejects address(0), so the treasury variable
    // must always hold a live address after construction.
    function invariant_treasuryNonZero() public view {
        assertTrue(settlement.treasury() != address(0));
    }

    // ── I7: Immutable addresses unchanged ─────────────────────────────────────
    //
    // TOKEN and REWARDS_POOL are set in the constructor via Solidity immutables;
    // there is no upgrade path or setter for either.
    function invariant_immutableAddressesUnchanged() public view {
        assertEq(address(settlement.TOKEN()),        address(token));
        assertEq(address(settlement.REWARDS_POOL()), address(rewards));
    }
}
