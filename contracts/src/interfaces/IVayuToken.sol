// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @title IVayuToken
/// @notice Interface for the Vayu protocol ERC-20 token.
///
///   Design: Fixed supply, minted once at deployment.
///   Total supply: 100,000,000 VAYU (100M tokens, 18 decimals).
///
///   Allocation at deployment:
///   ┌─────────────────────────┬────────┬────────────────────────────────┐
///   │ Bucket                  │ Share  │ Recipient                      │
///   ├─────────────────────────┼────────┼────────────────────────────────┤
///   │ Epoch Rewards Pool      │  60%   │ VayuRewards contract           │
///   │ Protocol Treasury       │  20%   │ Multisig / Timelock            │
///   │ Team / Dev              │  10%   │ Vesting contract (4yr, 1yr cliff)│
///   │ Community / Grants      │  10%   │ Multisig                       │
///   └─────────────────────────┴────────┴────────────────────────────────┘
///
///   No minting after deployment. No burn function. Immutable contract
///   (NOT upgradeable — ERC-20 token contracts must be immutable for trust).
interface IVayuToken {

    // ─────────────────────────────────────────────────────────────────────────
    // Standard ERC-20 (inherited, listed here for completeness)
    // ─────────────────────────────────────────────────────────────────────────

    function name()                                     external view returns (string memory);
    function symbol()                                   external view returns (string memory);
    function decimals()                                 external view returns (uint8);
    function totalSupply()                              external view returns (uint256);
    function balanceOf(address account)                 external view returns (uint256);
    function allowance(address owner, address spender)  external view returns (uint256);
    function approve(address spender, uint256 amount)   external returns (bool);
    function transfer(address to, uint256 amount)       external returns (bool);
    function transferFrom(address from, address to, uint256 amount) external returns (bool);

    event Transfer(address indexed from, address indexed to, uint256 value);
    event Approval(address indexed owner, address indexed spender, uint256 value);

    // ─────────────────────────────────────────────────────────────────────────
    // Protocol-specific views
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Returns the address of the epoch rewards pool contract.
    ///         Receives 60% of total supply at deployment.
    function rewardsPool()  external view returns (address);

    /// @notice Returns the address of the protocol treasury.
    ///         Receives 20% of total supply at deployment.
    function treasury()     external view returns (address);
}
