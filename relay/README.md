# Vayu Protocol Relay Service

## Local Development

### Prerequisites

- Java 21
- Maven 3.9+
- Docker (for local IPFS node)
- [Foundry](https://getfoundry.sh/) — `anvil`, `forge`, `cast` (required for on-chain commit mode only)

### Configuration

All runtime values are controlled via environment variables. Defaults are production-safe — override only what you need for local testing.

| Variable | Default | Description |
|---|---|---|
| `RELAY_PORT` | `3000` | HTTP listen port |
| `RELAY_LOG_LEVEL` | `INFO` | Log level for `protocol.vayu.*` (`DEBUG` for verbose) |
| **Epoch** | | |
| `RELAY_EPOCH_DURATION_SECONDS` | `3600` | Epoch length in seconds |
| `RELAY_EPOCH_COMMIT_CHECK_INTERVAL_MS` | `60000` | How often the commit worker polls for sealed epochs |
| `RELAY_EPOCH_TIMESTAMP_TOLERANCE_SECONDS` | `300` | Max clock skew accepted on reading timestamps |
| `RELAY_EPOCH_MIN_REPORTERS_PER_CELL` | `3` | Minimum reporters required for a cell to be active |
| `RELAY_EPOCH_SCORING_TOLERANCE_AQI` | `50` | AQI deviation before a reading is penalised |
| `RELAY_EPOCH_BUDGET_WEI` | `684931506849315068493` | Token reward budget per epoch (wei) |
| **Validation** | | |
| `RELAY_VALIDATION_H3_RESOLUTION` | `8` | Required H3 resolution for submitted readings |
| `RELAY_VALIDATION_RATE_LIMIT_WINDOW_SECONDS` | `300` | Per-reporter rate limit window |
| `RELAY_VALIDATION_MIN_AQI` | `1` | Minimum accepted AQI value |
| `RELAY_VALIDATION_MIN_PM25` | `1` | Minimum accepted PM2.5 value |
| **Security** | | |
| `RELAY_SECURITY_SIGNATURE_VERIFICATION_ENABLED` | `false` | Enforce EIP-712 signature verification |
| `RELAY_SECURITY_STAKE_CHECK_ENABLED` | `false` | Reject reporters with no on-chain stake |
| `RELAY_SECURITY_EIP712_CHAIN_ID` | `84532` | Chain ID used in EIP-712 domain separator |
| `RELAY_SECURITY_EIP712_VERIFYING_CONTRACT` | `0x000…` | Contract address in EIP-712 domain |
| **IPFS** | | |
| `RELAY_IPFS_PROVIDER` | `kubo` | IPFS backend: `kubo` (local) or `pinata` (managed) |
| `RELAY_IPFS_KUBO_API_URL` | `http://localhost:5001` | Kubo RPC API endpoint |
| `RELAY_IPFS_PINATA_JWT` | _(empty)_ | Pinata JWT (required when `RELAY_IPFS_PROVIDER=pinata`) |
| `RELAY_IPFS_PINATA_ENDPOINT` | `https://api.pinata.cloud/pinning/pinJSONToIPFS` | Pinata API endpoint |
| **Chain** | | |
| `RELAY_CHAIN_RPC_URL` | `http://localhost:8545` | EVM JSON-RPC endpoint |
| `RELAY_CHAIN_SETTLEMENT_ADDRESS` | `0x000…` | Deployed `VayuEpochSettlement` contract address |
| `RELAY_CHAIN_ON_CHAIN_COMMIT_ENABLED` | `false` | Submit real on-chain epoch commitments |
| `RELAY_CHAIN_RELAY_PRIVATE_KEY` | _(empty)_ | Relay wallet private key (hex, no `0x` prefix) |
| `RELAY_CHAIN_CHAIN_ID` | `84532` | EIP-155 chain ID for transaction signing |

---

## Mode A — Log-only (no chain required)

The default mode. Epoch commits are printed to the log instead of being submitted on-chain.
No Anvil, no contract deployment, no wallet needed. Best for testing the ingestion and
aggregation pipeline in isolation.

### 1. Start a local IPFS node

```bash
docker run -d -p 5001:5001 --name ipfs-kubo ipfs/kubo:latest
```

### 2. Configure and start the relay

```bash
cp .env.local.example .env.local
# .env.local already sets RELAY_EPOCH_DURATION_SECONDS=60 and
# RELAY_EPOCH_COMMIT_CHECK_INTERVAL_MS=5000 for fast local cycling.
source .env.local && mvn spring-boot:run
```

Confirm it is healthy:

```bash
curl -s http://localhost:3000/v1/health | jq .
```

### 3. Submit readings

Target the **current** (unsealed) epoch. Readings must arrive before the epoch seals.

```bash
SIG=$(python3 -c "print('0x' + 'ab'*65, end='')")
EPOCH_DURATION=${RELAY_EPOCH_DURATION_SECONDS:-60}
EPOCH=$(($(date +%s) / EPOCH_DURATION))
H3="0x08828308dfffffff"

for REPORTER in \
  "0x1111111111111111111111111111111111111111" \
  "0x2222222222222222222222222222222222222222" \
  "0x3333333333333333333333333333333333333333"; do
  curl -s -X POST http://localhost:3000/v1/readings \
    -H "Content-Type: application/json" \
    -d "{\"reporter\":\"$REPORTER\",\"h3Index\":\"$H3\",\"epochId\":$EPOCH,\"timestamp\":$(date +%s),\"aqi\":42,\"pm25\":15,\"signature\":\"$SIG\"}"
  echo
done
```

Each accepted reading responds with `{"status":"accepted","epochId":...}`.

### 4. Verify the commit

Wait up to 60 seconds for the epoch to seal. The relay logs the commit:

```
INFO  LoggingEpochCommitPublisher - epoch commit published: epochId=X, totalReadings=3,
  activeCells=1/1, rewards=3, penalty=0, dataRoot=0x..., rewardRoot=0x..., ipfsCid=Qm...
```

Fetch and inspect the pinned IPFS blob (replace `<CID>` with the value from the log):

```bash
curl -s -X POST "http://localhost:5001/api/v0/cat?arg=<CID>" | python3 -m json.tool
```

The blob contains the full epoch aggregate: cell scores, per-reporter rewards, Merkle roots, and the penalty list.

---

## Mode B — On-chain commit (local Anvil)

Submits a real `commitEpoch` transaction to `VayuEpochSettlement` via web3j.
Requires Anvil running locally and the contracts deployed. The relay startup guard
calls `isActiveRelay()` on the settlement contract before accepting traffic — the
deployer wallet must be registered as a relay first.

### 1. Start Anvil and a local IPFS node

```bash
anvil   # terminal 1 — keeps running
docker run -d -p 5001:5001 --name ipfs-kubo ipfs/kubo:latest
```

### 2. Deploy contracts

From the `contracts/` directory. `DEPLOY_FAUCET=true` seeds a testnet faucet with
100k VAYU; `REGISTER_RELAY=true` approves `MIN_RELAY_STAKE` (10k VAYU) and calls
`registerRelay()` so the relay startup guard passes immediately.

```bash
cd ../contracts

export DEPLOYER_PRIVATE_KEY=0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80

DEPLOY_FAUCET=true \
REGISTER_RELAY=true \
forge script script/DeployVayuCore.s.sol \
  --rpc-url http://127.0.0.1:8545 --broadcast -vvvv
```

The script prints a summary — note the `VayuEpochSettlement` address.
Anvil is deterministic: with the default account at nonce 0 these addresses are the same every time:

```
=== Vayu Protocol Deployment ===
Deployer:             0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
VayuRewards:          0x5FbDB2315678afecb367f032d93F642f64180aa3
VayuToken:            0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512
VayuEpochSettlement:  0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0
VayuFaucet:           0xCf7Ed3AccA5a467e9e704C703E8D87F634fB0Fc9
Relay registered:     0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
```

### 3. Configure and start the relay

Add the on-chain vars to `.env.local` (in addition to the base defaults from `.env.local.example`):

```bash
cd ../relay
cp .env.local.example .env.local
```

Append to `.env.local`:

```bash
# On-chain commit — Anvil
export RELAY_CHAIN_ON_CHAIN_COMMIT_ENABLED=true
export RELAY_CHAIN_RPC_URL=http://127.0.0.1:8545
export RELAY_CHAIN_CHAIN_ID=31337
# Private key without 0x prefix — use the same Anvil account used for deployment
export RELAY_CHAIN_RELAY_PRIVATE_KEY=ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80
# VayuEpochSettlement address from the deployment summary above
export RELAY_CHAIN_SETTLEMENT_ADDRESS=<settlement_address>
```

Start the relay:

```bash
source .env.local && mvn spring-boot:run
```

On startup the relay calls `isActiveRelay()` on the settlement contract. If the wallet
is registered you will see:

```
INFO  ChainConfig - Relay registration confirmed: 0xf39F... is active on 0x9fE4...
```

### 4. Submit readings

Same as Mode A — use the same curl loop with the current epoch ID:

```bash
SIG=$(python3 -c "print('0x' + 'ab'*65, end='')")
EPOCH_DURATION=${RELAY_EPOCH_DURATION_SECONDS:-60}
EPOCH=$(($(date +%s) / EPOCH_DURATION))
H3="0x08828308dfffffff"

for REPORTER in \
  "0x1111111111111111111111111111111111111111" \
  "0x2222222222222222222222222222222222222222" \
  "0x3333333333333333333333333333333333333333"; do
  curl -s -X POST http://localhost:3000/v1/readings \
    -H "Content-Type: application/json" \
    -d "{\"reporter\":\"$REPORTER\",\"h3Index\":\"$H3\",\"epochId\":$EPOCH,\"timestamp\":$(date +%s),\"aqi\":42,\"pm25\":15,\"signature\":\"$SIG\"}"
  echo
done
```

### 5. Verify the on-chain commit

Wait up to 60 seconds for the epoch to seal. The relay logs the transaction hash:

```
INFO  Web3jEpochCommitPublisher - commitEpoch submitted: epochId=X, txHash=0x..., ipfsCid=Qm...
```

Verify the transaction landed on Anvil:

```bash
cast receipt <tx_hash> --rpc-url http://127.0.0.1:8545
```

Inspect the commitment stored on-chain:

```bash
cast call <settlement_address> \
  "getEpochCommitment(uint32)((bytes32,bytes32,string,address,uint64,uint32,uint32,bool))" \
  <epoch_id> --rpc-url http://127.0.0.1:8545
```

Returns a tuple of `(dataRoot, rewardRoot, ipfsCid, relay, committedAt, totalReadings, activeCells, swept)`:

```
(0xb0f2aeb3..., 0x815bae63..., "QmRkCdBej...", 0xf39Fd6e5..., 1778401800, 3, 1, false)
```

Fetch and inspect the pinned IPFS blob:

```bash
curl -s -X POST "http://localhost:5001/api/v0/cat?arg=<CID>" | python3 -m json.tool
```

The blob is the canonical epoch aggregate. Sample output for a 3-reporter, 1-cell epoch:

```json
{
    "epochId": 29640029,
    "totalReadings": 3,
    "uniqueReporters": 3,
    "activeCells": 1,
    "dataRoot": "0xb0f2aeb3a70342edb39b790def6f9cf4a116abf6fd6e42e4b5183d36904636b4",
    "rewardRoot": "0x815bae632f2bead0e9c09060ec327b5efc5caf7620ddabda6792d5da9d9ed7f9",
    "cells": [
        {
            "h3Index": "0x08828308dfffffff",
            "readingCount": 3,
            "active": true,
            "medianAqi": 42,
            "avgPm25": 15,
            "avgPm10": 0,
            "avgO3": 0,
            "avgNo2": 0,
            "avgSo2": 0,
            "avgCo": 0,
            "reporterScores": [
                { "reporter": "0x1111111111111111111111111111111111111111", "score": 1.0 },
                { "reporter": "0x2222222222222222222222222222222222222222", "score": 1.0 },
                { "reporter": "0x3333333333333333333333333333333333333333", "score": 1.0 }
            ]
        }
    ],
    "rewards": [
        { "reporter": "0x1111...", "h3IndexLong": 613196573416882175, "amount": "223744292237442922374" },
        { "reporter": "0x2222...", "h3IndexLong": 613196573416882175, "amount": "223744292237442922374" },
        { "reporter": "0x3333...", "h3IndexLong": 613196573416882175, "amount": "223744292237442922374" }
    ],
    "penaltyList": []
}
```

`rewards[].amount` is in VAYU wei. Each reporter received an equal share of the epoch budget
(budget − relay fee) / 3 in this example. The `rewardRoot` is the Merkle root over these
leaves and is what reporters use to claim on-chain via `VayuEpochSettlement.claimReward()`.
