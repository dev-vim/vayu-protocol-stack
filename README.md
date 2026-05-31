# Vayu Protocol

<p align="center">
   <img src="docs/logo.png" height="400" alt="Vayu Protocol" />
</p>

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
| `prometheus` | Prometheus | 9090 |

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

### Submit readings

The Compose stack enables EIP-712 signature verification by default. Use the reporter
simulator to generate and submit properly signed readings:

```bash
cd reporter-simulator
pip install -r requirements.txt
python simulate.py
```

See [reporter-simulator/README.md](reporter-simulator/README.md) for all options including
multi-round simulation, tamper testing, and fixture generation.

### Watch the relay commit

The relay accumulates readings during each 60-second epoch window and commits at the
boundary. Follow the relay logs:

```bash
docker compose logs relay -f
```

Look for log lines referencing `commitEpoch` and an IPFS CID.

### View the dashboard

Open [http://localhost:3000](http://localhost:3000). Once at least one epoch has been
committed and indexed, the dashboard renders epoch history, reporter activity, and relay
statistics.

See the component READMEs below for full API details, GraphQL queries, and on-chain
verification commands.

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
- **Signature verification** — Enabled in the Compose stack (`RELAY_SECURITY_SIGNATURE_VERIFICATION_ENABLED=true`). Use the [reporter simulator](reporter-simulator/README.md) to generate properly signed readings. Set to `false` in compose for quick manual testing with unsigned payloads.
- **Stake checking** — Disabled by default. Reporter addresses in test readings do not need an on-chain stake balance.
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
- [reporter-simulator/README.md](reporter-simulator/README.md) — EIP-712 signing simulator for local testing
