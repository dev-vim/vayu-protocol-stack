// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @title IVayuRewards
/// @notice Interface for the epoch rewards escrow contract.
///
///   This contract holds the 60M VAYU token reward pool and releases a fixed
///   budget per epoch to VayuEpochSettlement. It is the "dripping tank."
///
///   Key properties:
///   - Immutable (NOT upgradeable). The release schedule is a protocol
///     guarantee; it must not be changeable.
///   - Only VayuEpochSettlement can trigger a release (via releaseEpochBudget).
///   - Flat linear schedule: totalBudget / TOTAL_EPOCHS per epoch.
///   - If a relay skips an epoch, that epoch's budget is NOT released
///     (it stays in the pool and the pool exhaust date extends slightly).
///     This prevents a slow relay from draining the pool ahead of schedule.
///
///   Schedule:
///     totalBudget   = 60,000,000 × 1e18 (60M VAYU)
///     TOTAL_EPOCHS  = 87,600  (10 years × 365 days × 24 hours)
///     epochBudget() ≈ 685 VAYU per epoch  (≈ 685 × 1e18 wei)
interface IVayuRewards {

    // ─────────────────────────────────────────────────────────────────────────
    // Events
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Emitted each time an epoch budget is released.
    event EpochBudgetReleased(
        uint32  indexed epochId,
        uint256         amount,
        address         recipient  // VayuEpochSettlement
    );

    // ─────────────────────────────────────────────────────────────────────────
    // Release Function
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Called by VayuEpochSettlement during commitEpoch() to pull
    ///         this epoch's token budget into the settlement contract.
    ///
    ///   Requirements:
    ///   - msg.sender must be the registered VayuEpochSettlement address
    ///   - epochId must not have been previously released
    ///   - Pool must not be exhausted (poolBalance > 0)
    ///
    ///   Emits EpochBudgetReleased.
    ///   Returns the actual amount transferred (= epochBudget() unless
    ///   the pool is nearly empty, in which case it returns the remainder).
    ///
    /// @param epochId The epoch being committed. Used to prevent double-release.
    function releaseEpochBudget(uint32 epochId) external returns (uint256 amount);

    // ─────────────────────────────────────────────────────────────────────────
    // Views
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Returns the fixed token budget released per epoch.
    ///         = totalBudget / TOTAL_EPOCHS (integer division).
    function epochBudget() external view returns (uint256);

    /// @notice Returns the current token balance remaining in the pool.
    function poolBalance() external view returns (uint256);

    /// @notice Returns the total number of epochs the pool is designed to fund.
    function TOTAL_EPOCHS() external view returns (uint32);

    /// @notice Returns the number of epochs that have been released so far.
    function epochsReleased() external view returns (uint32);

    /// @notice Returns true if the given epochId has already been released.
    function isReleased(uint32 epochId) external view returns (bool);

    /// @notice Returns the address of the VayuEpochSettlement contract
    ///         that is authorised to call releaseEpochBudget().
    function settlement() external view returns (address);

    /// @notice Returns the address of the VAYU ERC-20 token.
    function token() external view returns (address);
}
