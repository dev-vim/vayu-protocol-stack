# Vayu Protocol

Vayu is a DePIN (decentralised physical infrastructure) network for air quality monitoring. Edge devices submit cryptographically-signed AQI sensor readings to a relay, which aggregates them per-epoch, pins a data blob to IPFS, and commits a settlement transaction on-chain. A Ponder indexer watches the chain events and exposes a GraphQL API; a Next.js dashboard renders the indexed data.

```
Edge Device → Relay (write path) → EVM chain + IPFS
                                        ↓
                               Ponder Indexer (GraphQL)
                                        ↓
                               Dashboard (Next.js)
```

## Stack Overview

| Service | Technology | Port(s) |
|---|---|---|
| `anvil` | Local EVM (Foundry) | 8545 |
| `postgres` | PostgreSQL 15 | 5432 |
| `ipfs` | Kubo (IPFS) | 5001 (API), 8081 (HTTP gateway) |
| `contracts` | Forge deploy (one-shot) | — |
| `relay` | Spring Boot 3 / Java 21 | 8080 |
| `ponder` | Ponder 0.16 / Node 22 | 42069 |
| `sidecar` | IPFS blob fetcher (tsx) | — |
| `dashboard` | Next.js 15 / React 19 | 3000 |

Component-level documentation is linked at the bottom of this file.

---

## Prerequisites

- **Docker Engine 24+** (or Docker Desktop) with the Compose v2 plugin (`docker compose`)
- **4 GB+ RAM** available to Docker
- No other local dependencies — everything runs inside containers

---

## Local Deployment

### 1. Start the full stack

```bash
docker compose up --build
```

The first run builds the relay (Maven), indexer, and dashboard images. Subsequent starts reuse the cache and are significantly faster.

### 2. Startup sequence

Services come up in dependency order. The full stack is ready in roughly 2–3 minutes:

1. **anvil** — EVM chain starts and begins producing blocks on demand
2. **postgres** — database initialised with `vayu_indexer` schema
3. **ipfs** — Kubo node starts, API listening on port 5001
4. **contracts** — Forge deploys `VayuRewards`, `VayuToken`, `VayuEpochSettlement`, `VayuFaucet` then exits (`code 0`)
5. **relay** — Spring Boot starts, connects to Anvil and IPFS
6. **ponder** — begins syncing chain events from block 1, GraphQL API becomes available
7. **sidecar** — polls Ponder's database for new epoch commits, fetches blobs from IPFS
8. **dashboard** — Next.js production server starts

### 3. Confirm everything is up

```bash
docker compose ps
```

All services should show `running` (or `exited (0)` for the one-shot `contracts` service). You can also spot-check individual services:

```bash
# Relay health
curl -s http://localhost:8080/v1/health | jq .

# Ponder GraphQL liveness
curl -s http://localhost:42069/hello

# Dashboard
open http://localhost:3000   # or visit in a browser
```

---

## Testing the Stack

### Submit a reading to the relay

Signature verification and stake checking are disabled by default in the Compose configuration, so any structurally valid request is accepted.

`epochId` is derived from the current Unix timestamp divided by the epoch duration (60 seconds in the local dev stack).

```bash
NOW=$(date +%s)
EPOCH_ID=$(( NOW / 60 ))

curl -s -X POST http://localhost:8080/v1/readings \
  -H 'Content-Type: application/json' \
  -d "{
    \"reporter\": \"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266\",
    \"h3Index\": \"0x08828308281fffff\",
    \"epochId\": ${EPOCH_ID},
    \"timestamp\": ${NOW},
    \"aqi\": 42,
    \"pm25\": 18,
    \"signature\": \"0x0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\"
  }" | jq .
```

A successful submission returns:

```json
{"status": "accepted", "epochId": 12345, "receivedAt": 1700000000}
```

### Watch for an epoch commit

The relay accumulates readings during the current 60-second epoch window and commits at the epoch boundary. Follow the relay logs to watch the commit happen:

```bash
docker compose logs relay -f
```

Look for log lines referencing `commitEpoch` and an IPFS CID.

### Query the Ponder GraphQL API

After the relay commits, Ponder picks up the on-chain event and indexes it. Query recent epochs:

```bash
curl -s -X POST http://localhost:42069/graphql \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "{ epochss(limit: 5, orderBy: \"committedAt\", orderDirection: \"desc\") { items { epochId committedAt ipfsCid totalReward } } }"
  }' | jq .
```

### Verify the on-chain state directly

If you have [Foundry](https://book.getfoundry.sh/getting-started/installation) installed locally:

```bash
# Current block number
cast block-number --rpc-url http://localhost:8545

# Number of committed epochs
cast call 0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0 \
  "epochCount()(uint32)" \
  --rpc-url http://localhost:8545
```

If you don't have Foundry installed, you can run `cast` inside the already-running `anvil` container:

```bash
docker compose exec anvil \
  cast call 0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0 \
    "epochCount()(uint32)" \
    --rpc-url http://localhost:8545
```

### View the dashboard

Open [http://localhost:3000](http://localhost:3000). Once at least one epoch has been committed and indexed, the dashboard renders epoch history, reporter activity, and relay statistics.

---

## Lifecycle Management

### Stop the stack (preserve data)

```bash
docker compose down
```

PostgreSQL and IPFS volumes are preserved. The Anvil chain has no persistent volume — on the next `up`, the `contracts` service redeploys to the same deterministic addresses (nonce-0 deployments using the default Anvil private key).

### Stop and wipe all data

```bash
docker compose down -v
```

This removes `postgres_data` and `ipfs_data` volumes. Use this to start completely fresh.

### Follow logs

```bash
docker compose logs -f                    # all services
docker compose logs -f relay              # relay only
docker compose logs -f ponder sidecar     # indexer pipeline
```

### Rebuild after code changes

```bash
docker compose up --build
```

Only the images whose build context changed will be rebuilt.

---

## Dev Notes

- **Private keys** — The default Anvil mnemonic key (`0xac0974bec3...`) is used for both contract deployment and the relay's on-chain signer. This is intentional for local dev and **must never be used in production**.
- **Epoch duration** — Set to 60 seconds in the Compose stack (`RELAY_EPOCH_DURATION_SECONDS=60`). Production uses 3600 seconds (1 hour).
- **Signature verification** — Disabled by default (`RELAY_SECURITY_SIGNATURE_VERIFICATION_ENABLED` defaults to `false`). Enable it and provide valid EIP-712 signatures for integration testing of the full signing flow.
- **Stake checking** — Also disabled by default. The reporter address in test readings does not need an on-chain stake balance.
- **Contract addresses** — Deterministic because Anvil starts at nonce 0 and the deployer key is fixed. The addresses are hardcoded in the Compose environment:
  - `VayuRewards` → `0x5FbDB2315678afecb367f032d93F642f64180aa3`
  - `VayuToken` → `0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512`
  - `VayuEpochSettlement` → `0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0`
  - `VayuFaucet` → `0xCf7Ed3AccA5a467e9e704C703E8D87F634fB0Fc9`

---

## Component Documentation

- [contracts/README.md](contracts/README.md) — Foundry project, contract descriptions, deployment
- [relay/README.md](relay/README.md) — Spring Boot relay, API reference, configuration
- [indexer/README.md](indexer/README.md) — Ponder indexer, GraphQL schema, sidecar
- [dashboard/README.md](dashboard/README.md) — Next.js dashboard, environment variables
