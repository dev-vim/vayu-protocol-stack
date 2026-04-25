// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {VayuToken} from "../src/VayuToken.sol";
import {VayuFaucet} from "../src/VayuFaucet.sol";

contract VayuFaucetTest is Test {
    VayuToken  public token;
    VayuFaucet public faucet;

    address public treasury  = makeAddr("treasury");
    address public team      = makeAddr("team");
    address public community = makeAddr("community");

    uint256 public constant DRIP_AMOUNT = 500 * 1e18;
    uint256 public constant COOLDOWN    = 24 hours;
    uint256 public constant FUND_AMOUNT = 100_000 * 1e18;

    function setUp() public {
        // Warp past the cooldown so first drip won't revert
        // (Forge default timestamp is 1, which is < COOLDOWN)
        vm.warp(COOLDOWN + 1);

        faucet = new VayuFaucet(address(1)); // temp — need token address

        token  = new VayuToken(address(this), treasury, team, community);
        faucet = new VayuFaucet(address(token));

        // Fund the faucet from this contract's rewards allocation
        token.approve(address(faucet), FUND_AMOUNT);
        faucet.fund(FUND_AMOUNT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────

    function test_constructor_setsToken() public view {
        assertEq(address(faucet.TOKEN()), address(token));
    }

    function test_constructor_constants() public view {
        assertEq(faucet.DRIP_AMOUNT(), DRIP_AMOUNT);
        assertEq(faucet.COOLDOWN(), COOLDOWN);
    }

    function test_revert_constructor_zeroToken() public {
        vm.expectRevert(VayuFaucet.ZeroAddress.selector);
        new VayuFaucet(address(0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // drip — Happy Path
    // ─────────────────────────────────────────────────────────────────────────

    function test_drip_sendsTokens() public {
        address alice = makeAddr("alice");

        vm.prank(alice);
        faucet.drip();

        assertEq(token.balanceOf(alice), DRIP_AMOUNT);
    }

    function test_drip_updatesLastDrip() public {
        address alice = makeAddr("alice");
        uint256 ts = block.timestamp;

        vm.prank(alice);
        faucet.drip();

        assertEq(faucet.lastDrip(alice), ts);
    }

    function test_drip_emitsEvent() public {
        address alice = makeAddr("alice");

        vm.expectEmit(true, false, false, true);
        emit VayuFaucet.Dripped(alice, DRIP_AMOUNT);
        vm.prank(alice);
        faucet.drip();
    }

    function test_drip_decreasesFaucetBalance() public {
        uint256 balBefore = faucet.balance();
        address alice = makeAddr("alice");

        vm.prank(alice);
        faucet.drip();

        assertEq(faucet.balance(), balBefore - DRIP_AMOUNT);
    }

    function test_drip_afterCooldown() public {
        address alice = makeAddr("alice");

        vm.prank(alice);
        faucet.drip();
        assertEq(token.balanceOf(alice), DRIP_AMOUNT);

        // Warp past cooldown
        vm.warp(block.timestamp + COOLDOWN);

        vm.prank(alice);
        faucet.drip();
        assertEq(token.balanceOf(alice), DRIP_AMOUNT * 2);
    }

    function test_drip_multipleUsers() public {
        address alice = makeAddr("alice");
        address bob   = makeAddr("bob");

        vm.prank(alice);
        faucet.drip();

        vm.prank(bob);
        faucet.drip();

        assertEq(token.balanceOf(alice), DRIP_AMOUNT);
        assertEq(token.balanceOf(bob), DRIP_AMOUNT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // drip — Reverts
    // ─────────────────────────────────────────────────────────────────────────

    function test_revert_drip_cooldownNotElapsed() public {
        address alice = makeAddr("alice");

        vm.prank(alice);
        faucet.drip();

        uint256 nextDripAt = block.timestamp + COOLDOWN;

        vm.prank(alice);
        vm.expectRevert(abi.encodeWithSelector(VayuFaucet.CooldownNotElapsed.selector, nextDripAt));
        faucet.drip();
    }

    function test_revert_drip_cooldownOneSecondEarly() public {
        address alice = makeAddr("alice");

        vm.prank(alice);
        faucet.drip();

        // Warp to 1 second before cooldown expires
        vm.warp(block.timestamp + COOLDOWN - 1);

        vm.prank(alice);
        vm.expectRevert();
        faucet.drip();
    }

    function test_revert_drip_insufficientBalance() public {
        // Deploy a new faucet with no funds
        VayuFaucet emptyFaucet = new VayuFaucet(address(token));

        address alice = makeAddr("alice");
        vm.prank(alice);
        vm.expectRevert(VayuFaucet.InsufficientFaucetBalance.selector);
        emptyFaucet.drip();
    }

    function test_drip_succeedsAtExactCooldownBoundary() public {
        address alice = makeAddr("alice");

        vm.prank(alice);
        faucet.drip();

        // Warp to exactly lastDrip + COOLDOWN.
        // The faucet uses `<` so this timestamp is no longer within the cooldown.
        vm.warp(block.timestamp + COOLDOWN);

        vm.prank(alice);
        faucet.drip();
        assertEq(token.balanceOf(alice), DRIP_AMOUNT * 2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // fund
    // ─────────────────────────────────────────────────────────────────────────

    function test_fund_transfersTokens() public {
        uint256 extraFund = 10_000 * 1e18;
        uint256 balBefore = faucet.balance();

        token.approve(address(faucet), extraFund);
        faucet.fund(extraFund);

        assertEq(faucet.balance(), balBefore + extraFund);
    }

    function test_fund_emitsEvent() public {
        uint256 extraFund = 5_000 * 1e18;
        token.approve(address(faucet), extraFund);

        vm.expectEmit(true, false, false, true);
        emit VayuFaucet.FaucetFunded(address(this), extraFund);
        faucet.fund(extraFund);
    }

    function test_fund_anyoneCanFund() public {
        // Transfer some tokens to a random user first
        address funder = makeAddr("funder");
        uint256 amount = 1_000 * 1e18;
        // forge-lint: disable-next-item(erc20-unchecked-transfer) — OZ ERC-20 reverts on failure
        token.transfer(funder, amount);

        vm.startPrank(funder);
        token.approve(address(faucet), amount);
        faucet.fund(amount);
        vm.stopPrank();

        assertEq(token.balanceOf(funder), 0);
    }

    function test_fund_zeroAmount() public {
        uint256 balBefore = faucet.balance();
        token.approve(address(faucet), 0);
        faucet.fund(0);
        assertEq(faucet.balance(), balBefore);
    }

    function test_revert_fund_withoutApproval() public {
        address funder = makeAddr("funder");
        uint256 amount = 1_000 * 1e18;

        // forge-lint: disable-next-item(erc20-unchecked-transfer) — OZ ERC-20 reverts on failure
        token.transfer(funder, amount);

        vm.prank(funder);
        vm.expectRevert();
        faucet.fund(amount);
    }

    function test_revert_fund_insufficientBalance() public {
        address funder = makeAddr("funder");
        uint256 amount = 1_000 * 1e18;

        vm.prank(funder);
        token.approve(address(faucet), amount);

        vm.prank(funder);
        vm.expectRevert();
        faucet.fund(amount);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // balance view
    // ─────────────────────────────────────────────────────────────────────────

    function test_balance_matchesTokenBalance() public view {
        assertEq(faucet.balance(), token.balanceOf(address(faucet)));
    }

    function test_balance_afterDrip() public {
        address alice = makeAddr("alice");
        uint256 before = faucet.balance();

        vm.prank(alice);
        faucet.drip();

        assertEq(faucet.balance(), before - DRIP_AMOUNT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fuzz Tests
    // ─────────────────────────────────────────────────────────────────────────

    function testFuzz_drip_afterArbitraryCooldown(uint256 warpSeconds) public {
        warpSeconds = bound(warpSeconds, COOLDOWN, 365 days);
        address alice = makeAddr("alice");

        vm.prank(alice);
        faucet.drip();

        vm.warp(block.timestamp + warpSeconds);

        vm.prank(alice);
        faucet.drip();

        assertEq(token.balanceOf(alice), DRIP_AMOUNT * 2);
    }

    function testFuzz_revert_drip_beforeCooldown(uint256 warpSeconds) public {
        warpSeconds = bound(warpSeconds, 0, COOLDOWN - 1);
        address alice = makeAddr("alice");

        vm.prank(alice);
        faucet.drip();

        vm.warp(block.timestamp + warpSeconds);

        vm.prank(alice);
        vm.expectRevert();
        faucet.drip();
    }

    function testFuzz_fund_arbitraryAmount(uint256 amount) public {
        uint256 myBal = token.balanceOf(address(this));
        amount = bound(amount, 0, myBal);

        uint256 faucetBal = faucet.balance();
        token.approve(address(faucet), amount);
        faucet.fund(amount);

        assertEq(faucet.balance(), faucetBal + amount);
    }

    function testFuzz_drip_multipleUsersNoCooldownConflict(uint8 userCount) public {
        userCount = uint8(bound(userCount, 1, 50));

        for (uint8 i = 0; i < userCount; i++) {
            address user = makeAddr(string(abi.encodePacked("user", i)));
            vm.prank(user);
            faucet.drip();
            assertEq(token.balanceOf(user), DRIP_AMOUNT);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Edge: drain faucet then refund
    // ─────────────────────────────────────────────────────────────────────────

    function test_drainAndRefund() public {
        // Drain the faucet
        uint256 drips = FUND_AMOUNT / DRIP_AMOUNT;
        for (uint256 i = 0; i < drips; i++) {
            address user = makeAddr(string(abi.encodePacked("drainer", i)));
            vm.prank(user);
            faucet.drip();
        }

        assertEq(faucet.balance(), FUND_AMOUNT % DRIP_AMOUNT);

        // If fully drained, drip should revert
        if (faucet.balance() < DRIP_AMOUNT) {
            address newUser = makeAddr("newUser");
            vm.prank(newUser);
            vm.expectRevert(VayuFaucet.InsufficientFaucetBalance.selector);
            faucet.drip();
        }

        // Refund and drip again
        uint256 refund = 1_000 * 1e18;
        token.approve(address(faucet), refund);
        faucet.fund(refund);

        address finalUser = makeAddr("finalUser");
        vm.prank(finalUser);
        faucet.drip();
        assertEq(token.balanceOf(finalUser), DRIP_AMOUNT);
    }
}
