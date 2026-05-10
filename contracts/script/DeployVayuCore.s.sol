// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Script, console2} from "forge-std/Script.sol";
import {VayuToken} from "../src/VayuToken.sol";
import {VayuRewards} from "../src/VayuRewards.sol";
import {VayuEpochSettlement} from "../src/VayuEpochSettlement.sol";
import {VayuFaucet} from "../src/VayuFaucet.sol";

/**
 * @title DeployVayuCore
 * @notice Deploys the full Vayu Protocol contract suite in the correct order
 *         and optionally registers the deployer as a relay.
 *
 * Required env vars:
 *   DEPLOYER_PRIVATE_KEY  — hex private key of the deployer/relay wallet
 *
 * Optional env vars (default to deployer address for local dev):
 *   TREASURY              — protocol treasury address (receives 20% VAYU)
 *   TEAM                  — team address            (receives 10% VAYU)
 *   COMMUNITY             — community address       (receives 10% VAYU)
 *   DEPLOY_FAUCET         — if "true", deploy VayuFaucet and fund with 100k VAYU
 *   REGISTER_RELAY        — if "true", approve + call registerRelay() as deployer
 *
 * Local Anvil example:
 *   DEPLOYER_PRIVATE_KEY=<anvil_pk> \
 *   DEPLOY_FAUCET=true \
 *   REGISTER_RELAY=true \
 *   forge script script/DeployVayuCore.s.sol \
 *     --rpc-url http://127.0.0.1:8545 --broadcast -vvvv
 *
 * Deploy order and address pre-computation
 * ─────────────────────────────────────────
 * VayuRewards is deployed at nonce N but requires the VayuToken (N+1) and
 * VayuEpochSettlement (N+2) addresses at construction time (immutable state).
 * VayuToken is deployed at nonce N+1 and mints 60M tokens to the already-deployed
 * VayuRewards address. VayuEpochSettlement is deployed at nonce N+2 and receives
 * the already-deployed VayuToken and VayuRewards addresses.
 * vm.computeCreateAddress resolves the circular dependency without a proxy or
 * two-phase initialisation pattern.
 */
contract DeployVayuCore is Script {
    function run() external {
        uint256 deployerKey = vm.envUint("DEPLOYER_PRIVATE_KEY");
        address deployer    = vm.addr(deployerKey);

        address treasury  = vm.envOr("TREASURY",  deployer);
        address team      = vm.envOr("TEAM",      deployer);
        address community = vm.envOr("COMMUNITY", deployer);

        bool deployFaucet  = vm.envOr("DEPLOY_FAUCET",  false);
        bool registerRelay = vm.envOr("REGISTER_RELAY", false);

        // ── Pre-compute CREATE addresses ──────────────────────────────────────
        // Each deployment increments the nonce by 1, so we can predict addresses
        // before any transaction is sent.
        uint256 nonce = vm.getNonce(deployer);

        address predictedRewards    = vm.computeCreateAddress(deployer, nonce);
        address predictedToken      = vm.computeCreateAddress(deployer, nonce + 1);
        address predictedSettlement = vm.computeCreateAddress(deployer, nonce + 2);

        vm.startBroadcast(deployerKey);

        // ── 1. VayuRewards ────────────────────────────────────────────────────
        // Holds the 60M-token epoch reward escrow. SETTLEMENT is baked in as
        // immutable so only the settlement contract can trigger budget releases.
        VayuRewards rewards = new VayuRewards(predictedToken, predictedSettlement);
        require(address(rewards) == predictedRewards, "DeployVayuCore: VayuRewards address mismatch");

        // ── 2. VayuToken ──────────────────────────────────────────────────────
        // Fixed 100M supply minted on construction:
        //   60M → rewards pool (epoch escrow)
        //   20M → treasury
        //   10M → team
        //   10M → community
        VayuToken token = new VayuToken(address(rewards), treasury, team, community);
        require(address(token) == predictedToken, "DeployVayuCore: VayuToken address mismatch");

        // ── 3. VayuEpochSettlement ────────────────────────────────────────────
        VayuEpochSettlement settlement = new VayuEpochSettlement(
            address(token),
            address(rewards),
            treasury
        );
        require(address(settlement) == predictedSettlement, "DeployVayuCore: VayuEpochSettlement address mismatch");

        // ── 4. VayuFaucet (testnet / local dev only) ─────────────────────────
        if (deployFaucet) {
            VayuFaucet faucet = new VayuFaucet(address(token));
            // Seed faucet with 100k VAYU from the deployer's token allocation.
            // Requires deployer to hold tokens (true when treasury/team/community
            // all default to deployer).
            token.approve(address(faucet), 100_000 * 1e18);
            faucet.fund(100_000 * 1e18);
            console2.log("VayuFaucet:          ", address(faucet));
        }

        // ── 5. Relay registration (local dev convenience) ─────────────────────
        // Approves MIN_RELAY_STAKE (10k VAYU) and registers the deployer wallet
        // as an active relay so the Spring Boot relay can pass the startup guard.
        if (registerRelay) {
            uint256 minRelayStake = settlement.MIN_RELAY_STAKE();
            token.approve(address(settlement), minRelayStake);
            settlement.registerRelay();
        }

        vm.stopBroadcast();

        // ── Post-deploy summary ───────────────────────────────────────────────
        console2.log("=== Vayu Protocol Deployment ===");
        console2.log("Deployer:            ", deployer);
        console2.log("VayuRewards:         ", address(rewards));
        console2.log("VayuToken:           ", address(token));
        console2.log("VayuEpochSettlement: ", address(settlement));
        console2.log("Treasury:            ", treasury);
        if (registerRelay) {
            console2.log("Relay registered:    ", deployer);
        }
    }
}
