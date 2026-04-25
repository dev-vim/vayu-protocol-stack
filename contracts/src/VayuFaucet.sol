// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";

/// @title VayuFaucet
/// @notice Testnet-only faucet. Dispenses VAYU tokens for staking and testing.
contract VayuFaucet {
    using SafeERC20 for IERC20;

    IERC20  public immutable TOKEN;
    uint256 public constant DRIP_AMOUNT = 500 * 1e18; // 500 VAYU per drip
    uint256 public constant COOLDOWN = 24 hours;

    mapping(address => uint256) public lastDrip;

    event Dripped(address indexed recipient, uint256 amount);
    event FaucetFunded(address indexed funder, uint256 amount);

    error CooldownNotElapsed(uint256 nextDripAt);
    error InsufficientFaucetBalance();

    error ZeroAddress();

    constructor(address _token) {
        if (_token == address(0)) revert ZeroAddress();
        TOKEN = IERC20(_token);
    }

    /// @notice Send DRIP_AMOUNT tokens to msg.sender.
    function drip() external {
        if (block.timestamp < lastDrip[msg.sender] + COOLDOWN) {
            revert CooldownNotElapsed(lastDrip[msg.sender] + COOLDOWN);
        }
        if (TOKEN.balanceOf(address(this)) < DRIP_AMOUNT) {
            revert InsufficientFaucetBalance();
        }

        lastDrip[msg.sender] = block.timestamp;
        TOKEN.safeTransfer(msg.sender, DRIP_AMOUNT);
        emit Dripped(msg.sender, DRIP_AMOUNT);
    }

    /// @notice Top up the faucet. Caller must have approved this contract.
    function fund(uint256 amount) external {
        TOKEN.safeTransferFrom(msg.sender, address(this), amount);
        emit FaucetFunded(msg.sender, amount);
    }

    /// @notice Current faucet token balance.
    function balance() external view returns (uint256) {
        return TOKEN.balanceOf(address(this));
    }
}
