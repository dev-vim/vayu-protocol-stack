# Vayu Protocol Indexer

Ponder-based event indexer for `VayuEpochSettlement`. Listens to on-chain events and builds a queryable
read-model. Includes an **IPFS sidecar** that fetches each committed epoch blob, validates it, and
populates the `cell_epochs` and `readings` tables for downstream analytics and fisherman tooling.

## Components

| Component | Description |
|---|---|
| **Ponder indexer** (`npm run dev`) | Indexes on-chain events into PostgreSQL — epochs, reporters, relays, claims, challenges, slashes |
| **IPFS sidecar** (`npm run sidecar`) | Polls for `PENDING` epochs, fetches and validates the IPFS blob, writes per-cell and per-reading rows |

---

## Prerequisites

- Node.js 22+
- PostgreSQL 15+
- A running IPFS gateway (local Kubo or public)
- A running EVM node (Anvil for local dev, Base Sepolia for testnet)
- The `VayuEpochSettlement` contract deployed

---

## Configuration

Copy `.env.example` to `.env` and fill in the values:

```bash
cp .env.example .env
```

| Variable | Default | Description |
|---|---|---|
| `PONDER_RPC_URL_31337` | `http://localhost:8545` | EVM RPC for local Anvil (chain ID 31337) |
| `PONDER_RPC_URL_84532` | _(empty)_ | EVM RPC for Base Sepolia (chain ID 84532) |
| `DATABASE_URL` | `postgresql://postgres:postgres@localhost:5432/vayu_indexer` | PostgreSQL connection string |
| `VAYU_SETTLEMENT_ADDRESS` | `0x000…` | Deployed `VayuEpochSettlement` address |
| `VAYU_SETTLEMENT_START_BLOCK` | `1` | First block to index (skip pre-deployment history) |
| `IPFS_GATEWAY_URL` | `http://localhost:8080/ipfs` | Gateway used to fetch epoch blobs by CID |
| `SIDECAR_POLL_INTERVAL_MS` | `30000` | How often the sidecar polls for PENDING epochs |
| `SIDECAR_BATCH_SIZE` | `10` | Max epochs processed per poll cycle |
| `PONDER_PORT` | `42069` | Port for the Ponder GraphQL API and playground |

---

## Local Development

### 1. Start dependencies

```bash
# PostgreSQL
docker run -d \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=vayu_indexer \
  -p 5432:5432 \
  --name vayu-pg \
  postgres:15

# Local IPFS gateway (Kubo)
docker run -d \
  -p 5001:5001 \
  -p 8080:8080 \
  --name ipfs-kubo \
  ipfs/kubo:latest
```

### 2. Start Anvil and deploy contracts

```bash
anvil  # terminal 1

# From contracts/
export DEPLOYER_PRIVATE_KEY=0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80
DEPLOY_FAUCET=true REGISTER_RELAY=true \
  forge script script/DeployVayuCore.s.sol \
  --rpc-url http://127.0.0.1:8545 --broadcast -vvvv
```

Note the `VayuEpochSettlement` address from the deploy output and set `VAYU_SETTLEMENT_ADDRESS` in `.env`.

### 3. Install dependencies

```bash
npm install
```

### 4. Start the Ponder indexer

```bash
npm run dev
```

Ponder creates the database schema automatically on first run and begins syncing from `VAYU_SETTLEMENT_START_BLOCK`.
The GraphQL playground is available at `http://localhost:${PONDER_PORT:-42069}`.

### 5. Start the IPFS sidecar

In a separate terminal:

```bash
npm run sidecar
```

The sidecar runs migrations for the `cell_epochs` and `readings` tables on startup, then begins polling
for epochs with `ipfs_status = 'PENDING'`.

```
[sidecar] starting — running migrations
[sidecar] migrations done, beginning poll loop
[sidecar] processing 1 pending epoch(s)
[sidecar] epoch 494059: ingested — 3 readings, 1 cells
```

---

## Tables

### Ponder-managed (on-chain events)

| Table | Key | Description |
|---|---|---|
| `epochs` | `epoch_id` | One row per `EpochCommitted` event. `ipfs_status` tracks sidecar ingestion state. |
| `reporters` | `address` | Reporter staking state and lifetime totals |
| `relays` | `address` | Relay staking state and epoch commit counter |
| `claims` | `(epoch_id, reporter, cell_id)` | One row per `RewardClaimed` event |
| `challenges` | `(epoch_id, challenger, challenge_type)` | One row per `ChallengeSubmitted`, updated on `ChallengeResolved` |
| `slashes` | `(epoch_id, challenge_type, offender)` | One row per `Slashed` event |

### Sidecar-managed (IPFS blobs)

| Table | Key | Description |
|---|---|---|
| `cell_epochs` | `(epoch_id, h3_index)` | Per-cell aggregate: medianAqi, averages, reporter scores |
| `readings` | `(epoch_id, reporter, h3_index)` | Individual AQI readings as submitted by reporters |

---

## Verifying Data with psql

Connect to the database:

```bash
psql postgresql://postgres:postgres@localhost:5432/vayu_indexer
# or, if .env.local is sourced:
. ./.env.local && psql "$DATABASE_URL"
```

Useful queries:

```sql
-- Check epoch ingestion status
SELECT epoch_id, ipfs_status, ipfs_cid FROM epochs ORDER BY epoch_id DESC LIMIT 20;

-- List all tables (confirm sidecar tables exist)
\dt

-- Inspect per-cell aggregates for a specific epoch
SELECT * FROM cell_epochs WHERE epoch_id = <epoch_id>;

-- Inspect individual readings for a specific epoch
SELECT * FROM readings WHERE epoch_id = <epoch_id> LIMIT 20;

-- Count readings per reporter
SELECT reporter, COUNT(*) AS reading_count FROM readings GROUP BY reporter ORDER BY reading_count DESC;

-- Check live_query_tables (created by Ponder's trigger infrastructure)
SELECT * FROM live_query_tables;

-- Check the trigger Ponder installs on the epochs table
SELECT trigger_name, event_object_schema, action_statement
FROM information_schema.triggers
WHERE event_object_table = 'epochs';
```

One-liner (no REPL):

```bash
. ./.env.local && psql "$DATABASE_URL" -c \
  "SELECT epoch_id, ipfs_status FROM epochs ORDER BY epoch_id DESC LIMIT 10;"
```

---

## IPFS Blob Format

The sidecar validates every blob against `src/sidecar/blob.schema.ts` before writing to the database.
The schema mirrors `EpochBlobAssembler.java` in the relay exactly. A valid blob looks like:

```json
{
  "epochId": 494059,
  "totalReadings": 3,
  "uniqueReporters": 3,
  "activeCells": 1,
  "dataRoot": "0xb0f2aeb3...",
  "rewardRoot": "0x815bae63...",
  "cells": [
    {
      "h3Index": "0x0882830a1fffffff",
      "readingCount": 3,
      "active": true,
      "medianAqi": 42,
      "avgPm25": 15,
      "avgPm10": 0, "avgO3": 0, "avgNo2": 0, "avgSo2": 0, "avgCo": 0,
      "reporterScores": [
        { "reporter": "0x1111...", "score": 1.0 }
      ]
    }
  ],
  "readings": [
    {
      "reporter": "0x1111...", "h3Index": "0x0882830a1fffffff",
      "epochId": 494059, "timestamp": 1778401800,
      "aqi": 42, "pm25": 15, "pm10": 0, "o3": 0, "no2": 0, "so2": 0, "co": 0
    }
  ],
  "rewards": [
    { "reporter": "0x1111...", "h3IndexLong": 614894462665760767, "amount": "228310502283105022831" }
  ],
  "penaltyList": []
}
```

If validation fails, the epoch is marked `FAILED` and skipped in subsequent poll cycles.

---

## Tests

```bash
npm test
```

Runs Vitest across all test files. No database or network connections required — DB writes and IPFS
fetches are mocked.

```
 ✓ test/lib/epochs.test.ts        (9 tests)
 ✓ test/sidecar/blob.schema.test.ts  (20 tests)
 ✓ test/sidecar/ipfs.test.ts       (5 tests)
```

---

## Production (Base Sepolia)

1. Set `PONDER_RPC_URL_84532` to an Alchemy/Infura Base Sepolia endpoint
2. Set `DATABASE_URL` to a managed PostgreSQL instance
3. Set `VAYU_SETTLEMENT_ADDRESS` and `VAYU_SETTLEMENT_START_BLOCK` to the deployed contract values
4. Set `IPFS_GATEWAY_URL` to `https://gateway.pinata.cloud/ipfs` or a dedicated gateway
5. Run Ponder and the sidecar as separate processes (or containers)

```bash
# Indexer
npm run start

# Sidecar (separate process)
npm run sidecar
```
