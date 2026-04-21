// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {VayuToken} from "../src/VayuToken.sol";

contract VayuTokenTest is Test {
    VayuToken public token;

    address public rewardsPool = makeAddr("rewardsPool");
    address public treasury    = makeAddr("treasury");
    address public team        = makeAddr("team");
    address public community   = makeAddr("community");

    uint256 public constant TOTAL_SUPPLY = 100_000_000 * 1e18;

    function setUp() public {
        token = new VayuToken(rewardsPool, treasury, team, community);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor & Metadata
    // ─────────────────────────────────────────────────────────────────────────

    function test_name() public view {
        assertEq(token.name(), "Vayu");
    }

    function test_symbol() public view {
        assertEq(token.symbol(), "VAYU");
    }

    function test_decimals() public view {
        assertEq(token.decimals(), 18);
    }

    function test_totalSupply() public view {
        assertEq(token.totalSupply(), TOTAL_SUPPLY);
    }

    function test_totalSupplyConstant() public view {
        assertEq(token.TOTAL_SUPPLY(), TOTAL_SUPPLY);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Token Allocation
    // ─────────────────────────────────────────────────────────────────────────

    function test_rewardsPoolAllocation() public view {
        assertEq(token.balanceOf(rewardsPool), (TOTAL_SUPPLY * 60) / 100);
    }

    function test_treasuryAllocation() public view {
        assertEq(token.balanceOf(treasury), (TOTAL_SUPPLY * 20) / 100);
    }

    function test_teamAllocation() public view {
        assertEq(token.balanceOf(team), (TOTAL_SUPPLY * 10) / 100);
    }

    function test_communityAllocation() public view {
        assertEq(token.balanceOf(community), (TOTAL_SUPPLY * 10) / 100);
    }

    function test_allocationsSumToTotalSupply() public view {
        uint256 sum = token.balanceOf(rewardsPool)
                    + token.balanceOf(treasury)
                    + token.balanceOf(team)
                    + token.balanceOf(community);
        assertEq(sum, TOTAL_SUPPLY);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Immutables
    // ─────────────────────────────────────────────────────────────────────────

    function test_rewardsPoolImmutable() public view {
        assertEq(token.REWARDS_POOL(), rewardsPool);
    }

    function test_treasuryImmutable() public view {
        assertEq(token.TREASURY(), treasury);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor Reverts — Zero Address
    // ─────────────────────────────────────────────────────────────────────────

    function test_revert_zeroRewardsPool() public {
        vm.expectRevert(VayuToken.ZeroAddress.selector);
        new VayuToken(address(0), treasury, team, community);
    }

    function test_revert_zeroTreasury() public {
        vm.expectRevert(VayuToken.ZeroAddress.selector);
        new VayuToken(rewardsPool, address(0), team, community);
    }

    function test_revert_zeroTeam() public {
        vm.expectRevert(VayuToken.ZeroAddress.selector);
        new VayuToken(rewardsPool, treasury, address(0), community);
    }

    function test_revert_zeroCommunity() public {
        vm.expectRevert(VayuToken.ZeroAddress.selector);
        new VayuToken(rewardsPool, treasury, team, address(0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ERC-20 Core Functionality
    // ─────────────────────────────────────────────────────────────────────────

    function test_transfer() public {
        address alice = makeAddr("alice");
        uint256 amount = 1000 * 1e18;

        vm.prank(treasury);
        // forge-lint: disable-next-item(erc20-unchecked-transfer) — OZ ERC-20 reverts on failure
        token.transfer(alice, amount);

        assertEq(token.balanceOf(alice), amount);
        assertEq(token.balanceOf(treasury), (TOTAL_SUPPLY * 20) / 100 - amount);
    }

    function test_approve_and_transferFrom() public {
        address alice = makeAddr("alice");
        address bob   = makeAddr("bob");
        uint256 amount = 500 * 1e18;

        vm.prank(treasury);
        token.approve(alice, amount);

        vm.prank(alice);
        // forge-lint: disable-next-item(erc20-unchecked-transfer) — OZ ERC-20 reverts on failure
        token.transferFrom(treasury, bob, amount);

        assertEq(token.balanceOf(bob), amount);
        assertEq(token.allowance(treasury, alice), 0);
    }

    function test_revert_transferFromInsufficientAllowance() public {
        address spender = makeAddr("spender");
        address bob     = makeAddr("bob");

        vm.prank(treasury);
        token.approve(spender, 100);

        vm.prank(spender);
        vm.expectRevert();
        // forge-lint: disable-next-item(erc20-unchecked-transfer) — OZ ERC-20 reverts on failure
        token.transferFrom(treasury, bob, 101);
    }

    function test_revert_transferFromInsufficientOwnerBalance() public {
        address spender = makeAddr("spender");
        address owner   = makeAddr("owner");
        address bob     = makeAddr("bob");

        vm.prank(owner);
        token.approve(spender, 1);

        vm.prank(spender);
        vm.expectRevert();
        // forge-lint: disable-next-item(erc20-unchecked-transfer) — OZ ERC-20 reverts on failure
        token.transferFrom(owner, bob, 1);
    }

    function test_revert_transferInsufficientBalance() public {
        address alice = makeAddr("alice");
        address bob   = makeAddr("bob");

        vm.prank(alice);
        vm.expectRevert();
        // forge-lint: disable-next-item(erc20-unchecked-transfer) — OZ ERC-20 reverts on failure
        token.transfer(bob, 1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // No Mint / No Burn (fixed supply guarantee)
    // ─────────────────────────────────────────────────────────────────────────

    function test_noMintFunction() public view {
        // VayuToken has no public mint function — totalSupply is fixed.
        assertEq(token.totalSupply(), TOTAL_SUPPLY);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fuzz Tests
    // ─────────────────────────────────────────────────────────────────────────

    function testFuzz_constructorWithValidAddresses(
        address _rewards,
        address _treasury,
        address _team,
        address _community
    ) public {
        vm.assume(_rewards  != address(0));
        vm.assume(_treasury != address(0));
        vm.assume(_team     != address(0));
        vm.assume(_community != address(0));

        VayuToken t = new VayuToken(_rewards, _treasury, _team, _community);
        assertEq(t.totalSupply(), TOTAL_SUPPLY);
        assertEq(t.REWARDS_POOL(), _rewards);
        assertEq(t.TREASURY(), _treasury);
    }

    function testFuzz_transfer(uint256 amount) public {
        uint256 treasuryBal = token.balanceOf(treasury);
        amount = bound(amount, 0, treasuryBal);
        address recipient = makeAddr("recipient");

        vm.prank(treasury);
        // forge-lint: disable-next-item(erc20-unchecked-transfer) — OZ ERC-20 reverts on failure
        token.transfer(recipient, amount);

        assertEq(token.balanceOf(recipient), amount);
        assertEq(token.balanceOf(treasury), treasuryBal - amount);
        assertEq(token.totalSupply(), TOTAL_SUPPLY);
    }

    function testFuzz_approve_and_transferFrom(uint256 amount) public {
        uint256 treasuryBal = token.balanceOf(treasury);
        amount = bound(amount, 1, treasuryBal);
        address spender   = makeAddr("spender");
        address recipient = makeAddr("recipient");

        vm.prank(treasury);
        token.approve(spender, amount);
        assertEq(token.allowance(treasury, spender), amount);

        vm.prank(spender);
        // forge-lint: disable-next-item(erc20-unchecked-transfer) — OZ ERC-20 reverts on failure
        token.transferFrom(treasury, recipient, amount);

        assertEq(token.balanceOf(recipient), amount);
        assertEq(token.totalSupply(), TOTAL_SUPPLY);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Overlapping Addresses (edge case: same address for multiple roles)
    // ─────────────────────────────────────────────────────────────────────────

    function test_sameAddressMultipleRoles() public {
        address all = makeAddr("all");
        VayuToken t = new VayuToken(all, all, all, all);

        // All allocations go to the same address, so balance = totalSupply
        assertEq(t.balanceOf(all), TOTAL_SUPPLY);
        assertEq(t.totalSupply(), TOTAL_SUPPLY);
    }
}
