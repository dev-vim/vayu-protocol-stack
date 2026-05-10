import { createConfig } from "ponder";
import { http } from "viem";
import { VayuEpochSettlementAbi } from "./abis/VayuEpochSettlement";

// Contract address comes from the deploy output.
// For local Anvil runs, copy the address printed by `forge script DeployVayuCore`.
const settlementAddress = process.env.VAYU_SETTLEMENT_ADDRESS as `0x${string}`;

const startBlock = process.env.VAYU_SETTLEMENT_START_BLOCK
  ? parseInt(process.env.VAYU_SETTLEMENT_START_BLOCK, 10)
  : 1;

export default createConfig({
  chains: {
    // ── Local Anvil ─────────────────────────────────────────────────────────
    anvil: {
      id: 31337,
      rpc: http(process.env.PONDER_RPC_URL_31337 ?? "http://localhost:8545"),
    },

    // ── Base Sepolia testnet ─────────────────────────────────────────────────
    // baseSepolia: {
    //   id: 84532,
    //   rpc: http(process.env.PONDER_RPC_URL_84532),
    // },

    // ── Base mainnet ─────────────────────────────────────────────────────────
    // base: {
    //   id: 8453,
    //   rpc: http(process.env.PONDER_RPC_URL_8453),
    // },
  },

  contracts: {
    VayuEpochSettlement: {
      abi: VayuEpochSettlementAbi,
      chain: "anvil",
      address: settlementAddress,
      startBlock,
    },
  },
});
