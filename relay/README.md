# Vayu Protocol Stack Relay Service

## Local Development

### Prerequisites

- Java 21
- Maven 3.9+
- Docker (for local IPFS node)

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
| **Chain** | | |
| `RELAY_CHAIN_RPC_URL` | `http://localhost:8545` | EVM JSON-RPC endpoint |
| `RELAY_CHAIN_SETTLEMENT_ADDRESS` | `0x000…` | Deployed `VayuEpochSettlement` contract address |
| `RELAY_CHAIN_ON_CHAIN_COMMIT_ENABLED` | `false` | Submit real on-chain epoch commitments |
| `RELAY_CHAIN_RELAY_PRIVATE_KEY` | _(empty)_ | Relay wallet private key (hex, no `0x` prefix) |
| `RELAY_CHAIN_CHAIN_ID` | `84532` | EIP-155 chain ID for transaction signing |

For local testing, copy and edit the provided example env file:

```bash
cp .env.local.example .env.local
# .env.local as needed
```

---

### 1. Start a local IPFS node

```bash
docker run -d -p 5001:5001 --name ipfs-kubo ipfs/kubo:latest
```

### 2. Start the relay

From the `relay/` directory. Use short epoch durations for faster local testing:

```bash
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
EPOCH_DURATION=${RELAY_EPOCH_DURATION_SECONDS:-3600}
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
  activeCells=1/1, rewards=3, penalty=0, dataRoot=0x..., rewardRoot=0x..., ipfsCid=Qm..., txHash=0x...
```

Fetch and inspect the pinned IPFS blob (replace `<CID>` with the value from the log):

```bash
curl -s -X POST "http://localhost:5001/api/v0/cat?arg=<CID>" | python3 -m json.tool
```

The blob contains the full epoch aggregate: cell scores, per-reporter rewards, Merkle roots, and the penalty list.
