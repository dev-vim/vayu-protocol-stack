// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {StdInvariant} from "forge-std/StdInvariant.sol";

import {VayuToken} from "../src/VayuToken.sol";
import {VayuRewards} from "../src/VayuRewards.sol";

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
