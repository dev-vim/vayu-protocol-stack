import { createConfig } from "ponder";
import { http } from "viem";
import { VayuEpochSettlementAbi } from "./abis/VayuEpochSettlement";

console.log(`
                                                                 
                                                                 
                                                                 
                                                                 
                         ---------                               
                        -----------                              
                        ---     ----                             
                        ---     ---- ---------                   
                               -----------------                 
                 -----------------  ---     ----                 
                                    ---      ---                 
                                           -----                 
                 -----------------------------                   
                  -------------------------                      
                  -------------------------                      
                 -----------------------------                   
                                           -----                 
                                    ---      ---                 
                                    ---     ----                 
                                    ------------                 
                                     ---------                   
                                                                 
                                                                 
                                                                 
                                                                 
            ----    ----   -   ----   --------   ----            
             ---     --   ---    --   --  ---     --             
              --    --   ----     -- --   ---     --             
               --  --   --  --     ---    ---     --             
               --- -   --------    ---    ---     --             
                ---    --    --    ---     --     --             
                 --  ----   ----- -----     ------               
                                                                 
                                                                 
`);

// Contract address comes from the deploy output.
// For local Anvil runs, copy the address printed by `forge script DeployVayuCore`.
const settlementAddress = process.env.VAYU_SETTLEMENT_ADDRESS as `0x${string}`;

const startBlock = process.env.VAYU_SETTLEMENT_START_BLOCK
  ? parseInt(process.env.VAYU_SETTLEMENT_START_BLOCK, 10)
  : 1;

const port = process.env.PONDER_PORT
  ? parseInt(process.env.PONDER_PORT, 10)
  : 42069;

// Select active chain via PONDER_CHAIN env var.
// Defaults to "anvil" for local development.
//   PONDER_CHAIN=anvil        → local Anvil node
//   PONDER_CHAIN=baseSepolia  → Base Sepolia testnet (set PONDER_RPC_URL_84532)
//   PONDER_CHAIN=base         → Base mainnet        (set PONDER_RPC_URL_8453)
const activeChain = (process.env.PONDER_CHAIN ?? "anvil") as
  | "anvil"
  | "baseSepolia"
  | "base";

// Only register the active chain so Ponder does not attempt to connect to
// chains that are not running in the current environment.
const chainDefs = {
  anvil: {
    id: 31337,
    rpc: http(process.env.PONDER_RPC_URL_31337 ?? "http://localhost:8545"),
  },
  baseSepolia: {
    id: 84532,
    rpc: http(process.env.PONDER_RPC_URL_84532),
  },
  base: {
    id: 8453,
    rpc: http(process.env.PONDER_RPC_URL_8453),
  },
} as const;

export default createConfig({
  port,
  chains: {
    [activeChain]: chainDefs[activeChain],
  },

  contracts: {
    VayuEpochSettlement: {
      abi: VayuEpochSettlementAbi,
      chain: activeChain,
      address: settlementAddress,
      startBlock,
    },
  },
});
