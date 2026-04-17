// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";

/// @title VayuRewards
/// @notice Immutable epoch rewards escrow. Holds the 60M VAYU reward pool and
///         releases a fixed budget per epoch to the settlement contract.
///         NOT upgradeable — the release schedule is a protocol guarantee.
contract VayuRewards {
    using SafeERC20 for IERC20;

    IERC20  public immutable TOKEN;
    address public immutable SETTLEMENT;

    uint32 public constant TOTAL_EPOCHS = 87_600; // 10 years × 365 days × 24 hours

    uint256 public immutable EPOCH_BUDGET;
    uint32  public epochsReleased;

    mapping(uint32 => bool) public isReleased;

    event EpochBudgetReleased(uint32 indexed epochId, uint256 amount, address recipient);

    error OnlySettlement();
    error EpochAlreadyReleased(uint32 epochId);
    error PoolExhausted();

    error ZeroAddress();

    constructor(address _token, address _settlement) {
        if (_token == address(0)) revert ZeroAddress();
        if (_settlement == address(0)) revert ZeroAddress();

        TOKEN = IERC20(_token);
        SETTLEMENT = _settlement;

        // EPOCH_BUDGET = totalPoolBalance / TOTAL_EPOCHS
        // At deployment time the pool hasn't been funded yet, so we compute
        // from the known allocation: 60M tokens.
        EPOCH_BUDGET = (60_000_000 * 1e18) / TOTAL_EPOCHS;
    }

    /// @notice Called by VayuEpochSettlement during commitEpoch().
    function releaseEpochBudget(uint32 epochId) external returns (uint256 amount) {
        if (msg.sender != SETTLEMENT) revert OnlySettlement();
        if (isReleased[epochId]) revert EpochAlreadyReleased(epochId);

        uint256 bal = TOKEN.balanceOf(address(this));
        if (bal == 0) revert PoolExhausted();

        amount = EPOCH_BUDGET > bal ? bal : EPOCH_BUDGET;

        isReleased[epochId] = true;
        unchecked { epochsReleased++; }

        TOKEN.safeTransfer(SETTLEMENT, amount);
        emit EpochBudgetReleased(epochId, amount, SETTLEMENT);
    }

    /// @notice Current token balance remaining in the pool.
    function poolBalance() external view returns (uint256) {
        return TOKEN.balanceOf(address(this));
    }
}
