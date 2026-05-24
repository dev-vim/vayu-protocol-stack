# Vayu Reporter Simulator

Python script that generates EIP-712 signed AQI readings and submits them to the relay.
Useful for local end-to-end testing, load simulation, and generating fixture payloads for
Spring integration tests.

All private keys are Anvil's deterministic test accounts — **never use in production**.

## Requirements

- Python 3.10+
- `pip install -r requirements.txt`

## Usage

### Quick start (Docker Compose stack must be running)

```bash
python simulate.py
```

Submits one round of readings from 3 reporters to 2 H3 cells against the local relay.

### One cell per reporter (avoids rate-limit within a round)

```bash
python simulate.py --cells 1
```

### Multiple rounds spaced by the rate-limit window

```bash
python simulate.py --rounds 3 --cells 1 --interval 30
```

Each round waits `--interval` seconds before submitting again. The local compose stack
sets `RELAY_VALIDATION_RATE_LIMIT_WINDOW_SECONDS=30`, so `--interval 30` keeps every
round within the window.

### Test that the relay correctly rejects bad signatures

```bash
# Submit valid readings first, then wait past the rate-limit window, then tamper
python simulate.py --cells 1
sleep 31
python simulate.py --cells 1 --tamper
```

`--tamper` flips the last byte of every computed signature before submission.
With `RELAY_SECURITY_SIGNATURE_VERIFICATION_ENABLED=true` (the compose default),
each reading should return `400` — confirmed in the output as:

```
reporter-1 @ 0x0882830a1fffffff  aqi=57  → ✓ 400 rejected (tampered signature correctly refused)
```

If the relay unexpectedly accepts a tampered signature the output flags it:

```
reporter-1 @ 0x0882830a1fffffff  aqi=57  → ✗ UNEXPECTED ACCEPT — relay should have rejected tampered signature!
```

### Generate signed fixture payloads for Spring integration tests

```bash
python simulate.py \
  --chain-id 31337 \
  --verifying-contract 0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0 \
  --output-fixtures ../relay/src/test/resources/fixtures/signed_readings.json
```

`--output-fixtures` implies `--dry-run` — no readings are submitted. The output file
contains a JSON array of fully signed payloads that can be loaded directly in a Spring
`@ActiveProfiles("sig-enabled")` test.

## Options

| Flag | Default | Description |
|---|---|---|
| `--relay-url` | `http://localhost:8080` | Relay base URL |
| `--chain-id` | `31337` | EIP-712 chain ID — must match `RELAY_SECURITY_EIP712_CHAIN_ID` |
| `--verifying-contract` | `0x9fE46736...` | EIP-712 verifying contract — deterministic Anvil deploy |
| `--epoch-duration` | `60` | Epoch length in seconds — must match `RELAY_EPOCH_DURATION_SECONDS` |
| `--reporters` | `3` | Number of reporters to simulate (1–5) |
| `--cells` | `2` | H3 cells per reporter per round (1–6) |
| `--rounds` | `1` | Number of submission rounds |
| `--interval` | `30` | Seconds to wait between rounds |
| `--tamper` | off | Flip last byte of every signature before submitting |
| `--dry-run` | off | Sign readings but do not submit |
| `--output-fixtures FILE` | — | Write signed payloads to JSON file (implies `--dry-run`) |

## EIP-712 domain (local Anvil)

| Field | Value |
|---|---|
| `name` | `VayuProtocol` |
| `version` | `1` |
| `chainId` | `31337` |
| `verifyingContract` | `0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0` |

The struct type and field order match `Eip712SignatureVerifier.java` exactly — any
mismatch in field order or Solidity types produces a different digest and the relay
returns `400 invalid signature`.

## Reporters

Five Anvil test accounts (keys 1–5; key 0 is the relay/deployer):

| Label | Address |
|---|---|
| reporter-1 | `0x70997970C51812dc3A010C7d01b50e0d17dc79C8` |
| reporter-2 | `0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC` |
| reporter-3 | `0x90F79bf6EB2c4f870365E785982E1f101E93b906` |
| reporter-4 | `0x15d34AAf54267DB7D7c367839AAf71A00a2C6A65` |
| reporter-5 | `0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc` |
