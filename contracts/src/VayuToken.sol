// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";

/// @title VayuToken
/// @notice Fixed-supply ERC-20 token for the Vayu AQI protocol.
///         100M tokens minted at deployment, no further minting or burning.
contract VayuToken is ERC20 {
    uint256 public constant TOTAL_SUPPLY = 100_000_000 * 1e18; // 100M tokens

    address public immutable REWARDS_POOL;
    address public immutable TREASURY;

    error ZeroAddress();

    /// @param _rewardsPool  Receives 60% (epoch reward escrow).
    /// @param _treasury     Receives 20% (protocol treasury / multisig).
    /// @param _team         Receives 10% (team vesting).
    /// @param _community    Receives 10% (community / grants).
    constructor(
        address _rewardsPool,
        address _treasury,
        address _team,
        address _community
    ) ERC20("Vayu", "VAYU") {
        if (_rewardsPool == address(0)) revert ZeroAddress();
        if (_treasury == address(0)) revert ZeroAddress();
        if (_team == address(0)) revert ZeroAddress();
        if (_community == address(0)) revert ZeroAddress();

        REWARDS_POOL = _rewardsPool;
        TREASURY = _treasury;

        _mint(_rewardsPool, (TOTAL_SUPPLY * 60) / 100); // 60M
        _mint(_treasury,    (TOTAL_SUPPLY * 20) / 100); // 20M
        _mint(_team,        (TOTAL_SUPPLY * 10) / 100); // 10M
        _mint(_community,   (TOTAL_SUPPLY * 10) / 100); // 10M
    }
}
