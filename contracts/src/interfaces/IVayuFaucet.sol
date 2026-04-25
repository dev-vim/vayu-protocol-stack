// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @title IVayuFaucet
/// @notice Interface for the testnet token faucet contract.
///
///   PoC / testnet only. Funded with testnet VAYU tokens at deployment.
///   Users call drip() to receive tokens for staking and testing.
///   A per-address cooldown prevents a single address from draining the faucet.
///
///   This contract is NOT deployed to mainnet.
interface IVayuFaucet {

    // ─────────────────────────────────────────────────────────────────────────
    // Events
    // ─────────────────────────────────────────────────────────────────────────

    event Dripped(address indexed recipient, uint256 amount);
    event FaucetFunded(address indexed funder, uint256 amount);

    // ─────────────────────────────────────────────────────────────────────────
    // Core
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Send DRIP_AMOUNT tokens to msg.sender.
    ///
    ///   Requirements:
    ///   - block.timestamp >= lastDrip[msg.sender] + COOLDOWN
    ///   - faucet balance >= DRIP_AMOUNT
    ///
    ///   Intended onboarding flow:
    ///   1. New reporter calls drip() → receives 500 VAYU
    ///   2. Reporter calls token.approve(settlement, 200)
    ///   3. Reporter calls settlement.stakeFor(deviceAddress, 200)
    ///   4. Device starts submitting signed readings
    function drip() external;

    /// @notice Add tokens to the faucet balance (owner or anyone can top it up).
    ///         Caller must have pre-approved this contract for `amount`.
    function fund(uint256 amount) external;

    // ─────────────────────────────────────────────────────────────────────────
    // Views
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Tokens dispensed per drip call.
    function DRIP_AMOUNT() external view returns (uint256);

    /// @notice Seconds between allowed drip calls per address.
    function COOLDOWN()    external view returns (uint256);

    /// @notice Timestamp of the last drip for a given address. 0 if never dripped.
    function lastDrip(address account) external view returns (uint256);

    /// @notice Returns the VAYU token address.
    function token()       external view returns (address);

    /// @notice Returns the current faucet token balance.
    function balance()     external view returns (uint256);
}
