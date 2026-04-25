// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {VayuToken} from "../src/VayuToken.sol";
import {VayuRewards} from "../src/VayuRewards.sol";

contract VayuRewardsTest is Test {
    VayuToken   public token;
    VayuRewards public rewards;

    address public settlement = makeAddr("settlement");
    address public treasury   = makeAddr("treasury");
    address public team       = makeAddr("team");
    address public community  = makeAddr("community");

    uint256 public constant POOL_ALLOCATION = 60_000_000 * 1e18;

    function setUp() public {
        // Deploy token with a dummy rewards address, then deploy rewards,
        // then use deal() to fund the rewards contract with the 60M allocation.
        token   = new VayuToken(makeAddr("dummyRewards"), treasury, team, community);
        rewards = new VayuRewards(address(token), settlement);
        deal(address(token), address(rewards), POOL_ALLOCATION);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────

    function test_constructor_setsImmutables() public view {
        assertEq(address(rewards.TOKEN()), address(token));
        assertEq(rewards.SETTLEMENT(), settlement);
    }

    function test_constructor_computesEpochBudget() public view {
        uint256 expected = POOL_ALLOCATION / rewards.TOTAL_EPOCHS();
        assertEq(rewards.EPOCH_BUDGET(), expected);
    }

    function test_constructor_totalEpochs() public view {
        assertEq(rewards.TOTAL_EPOCHS(), 87_600);
    }

    function test_constructor_initialState() public view {
        assertEq(rewards.epochsReleased(), 0);
        assertEq(rewards.poolBalance(), POOL_ALLOCATION);
    }

    function test_revert_constructor_zeroToken() public {
        vm.expectRevert(VayuRewards.ZeroAddress.selector);
        new VayuRewards(address(0), settlement);
    }

    function test_revert_constructor_zeroSettlement() public {
        vm.expectRevert(VayuRewards.ZeroAddress.selector);
        new VayuRewards(address(1), address(0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // releaseEpochBudget — Happy Path
    // ─────────────────────────────────────────────────────────────────────────

    function test_releaseEpochBudget_transfersTokens() public {
        uint256 budgetBefore = rewards.poolBalance();

        vm.prank(settlement);
        uint256 amount = rewards.releaseEpochBudget(1);

        assertEq(amount, rewards.EPOCH_BUDGET());
        assertEq(token.balanceOf(settlement), amount);
        assertEq(rewards.poolBalance(), budgetBefore - amount);
    }

    function test_releaseEpochBudget_setsIsReleased() public {
        assertFalse(rewards.isReleased(1));

        vm.prank(settlement);
        rewards.releaseEpochBudget(1);

        assertTrue(rewards.isReleased(1));
    }

    function test_releaseEpochBudget_incrementsEpochsReleased() public {
        assertEq(rewards.epochsReleased(), 0);

        vm.prank(settlement);
        rewards.releaseEpochBudget(1);
        assertEq(rewards.epochsReleased(), 1);

        vm.prank(settlement);
        rewards.releaseEpochBudget(2);
        assertEq(rewards.epochsReleased(), 2);
    }

    function test_releaseEpochBudget_emitsEvent() public {
        vm.expectEmit(true, false, false, true);
        emit VayuRewards.EpochBudgetReleased(1, rewards.EPOCH_BUDGET(), settlement);
        vm.prank(settlement);
        rewards.releaseEpochBudget(1);
    }

    function test_releaseEpochBudget_multipleEpochs() public {
        uint256 budget = rewards.EPOCH_BUDGET();

        for (uint32 i = 1; i <= 5; i++) {
            vm.prank(settlement);
            uint256 amount = rewards.releaseEpochBudget(i);
            assertEq(amount, budget);
        }

        assertEq(rewards.epochsReleased(), 5);
        assertEq(token.balanceOf(settlement), budget * 5);
    }

    function test_releaseEpochBudget_nonSequentialEpochIds() public {

        vm.prank(settlement);
        rewards.releaseEpochBudget(100);
        assertTrue(rewards.isReleased(100));

        vm.prank(settlement);
        rewards.releaseEpochBudget(5);
        assertTrue(rewards.isReleased(5));

        assertEq(rewards.epochsReleased(), 2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // releaseEpochBudget — Reverts
    // ─────────────────────────────────────────────────────────────────────────

    function test_revert_releaseEpochBudget_notSettlement() public {
        vm.prank(makeAddr("attacker"));
        vm.expectRevert(VayuRewards.OnlySettlement.selector);
        rewards.releaseEpochBudget(1);
    }

    function test_revert_releaseEpochBudget_alreadyReleased() public {
        vm.prank(settlement);
        rewards.releaseEpochBudget(1);

        vm.prank(settlement);
        vm.expectRevert(abi.encodeWithSelector(VayuRewards.EpochAlreadyReleased.selector, uint32(1)));
        rewards.releaseEpochBudget(1);
    }

    function test_revert_releaseEpochBudget_poolExhausted() public {
        // Simulate empty pool: deploy a fresh rewards with a token that has 0 balance
        VayuToken dummyToken = new VayuToken(makeAddr("rp"), treasury, team, community);
        VayuRewards emptyRewards = new VayuRewards(address(dummyToken), settlement);
        // emptyRewards holds 0 tokens

        vm.prank(settlement);
        vm.expectRevert(VayuRewards.PoolExhausted.selector);
        emptyRewards.releaseEpochBudget(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // releaseEpochBudget — Edge: partial last release
    // ─────────────────────────────────────────────────────────────────────────

    function test_releaseEpochBudget_partialLastRelease() public {
        uint256 budget = rewards.EPOCH_BUDGET();

        // Transfer most tokens out to leave less than one budget
        uint256 balance = rewards.poolBalance();
        uint256 dust = balance % budget;
        uint256 toLeave = dust > 0 ? dust : budget / 2;

        // Use deal to set exact balance
        deal(address(token), address(rewards), toLeave);

        vm.prank(settlement);
        uint256 amt = rewards.releaseEpochBudget(1);
        assertEq(amt, toLeave);
        assertEq(rewards.poolBalance(), 0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // poolBalance view
    // ─────────────────────────────────────────────────────────────────────────

    function test_poolBalance_decreasesPerRelease() public {
        uint256 initial = rewards.poolBalance();
        uint256 budget = rewards.EPOCH_BUDGET();

        vm.prank(settlement);
        rewards.releaseEpochBudget(1);

        assertEq(rewards.poolBalance(), initial - budget);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fuzz Tests
    // ─────────────────────────────────────────────────────────────────────────

    function testFuzz_releaseEpochBudget_anyEpochId(uint32 epochId) public {
        vm.prank(settlement);
        uint256 amount = rewards.releaseEpochBudget(epochId);

        assertEq(amount, rewards.EPOCH_BUDGET());
        assertTrue(rewards.isReleased(epochId));
        assertEq(rewards.epochsReleased(), 1);
    }

    function testFuzz_releaseEpochBudget_uniqueIds(uint32 id1, uint32 id2) public {
        vm.assume(id1 != id2);

        vm.prank(settlement);
        rewards.releaseEpochBudget(id1);

        vm.prank(settlement);
        rewards.releaseEpochBudget(id2);

        assertTrue(rewards.isReleased(id1));
        assertTrue(rewards.isReleased(id2));
        assertEq(rewards.epochsReleased(), 2);
    }

    function testFuzz_revert_releaseEpochBudget_duplicateId(uint32 epochId) public {
        vm.prank(settlement);
        rewards.releaseEpochBudget(epochId);

        vm.prank(settlement);
        vm.expectRevert(abi.encodeWithSelector(VayuRewards.EpochAlreadyReleased.selector, epochId));
        rewards.releaseEpochBudget(epochId);
    }

    function testFuzz_revert_releaseEpochBudget_wrongCaller(address caller) public {
        vm.assume(caller != settlement);

        vm.prank(caller);
        vm.expectRevert(VayuRewards.OnlySettlement.selector);
        rewards.releaseEpochBudget(1);
    }
}
