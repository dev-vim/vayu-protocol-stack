# Vayu Protocol Contracts

Solidity smart contracts for the Vayu AQI protocol, built with [Foundry](https://book.getfoundry.sh/).

## Contracts

| Contract | Description |
|---|---|
| `VayuToken` | Fixed-supply ERC-20 (100M VAYU). Mints on construction: 60% rewards pool, 20% treasury, 10% team, 10% community. |
| `VayuRewards` | Immutable epoch rewards escrow. Holds the 60M VAYU reward pool and releases a fixed budget per epoch to the settlement contract. |
| `VayuEpochSettlement` | Core protocol contract. Accepts epoch commitments from relays, manages reporter/relay staking and slashing, and distributes rewards via Merkle proofs. |
| `VayuFaucet` | Testnet-only faucet. Dispenses 500 VAYU per drip with a 24-hour cooldown. |

## Prerequisites

- [Foundry](https://getfoundry.sh/) (`forge`, `cast`, `anvil`)

## Build

```shell
forge build
```

## Test

```shell
forge test
```

Run with verbose output and gas reporting:

```shell
forge test -vvv --gas-report
```

## Format

```shell
forge fmt
```

## Deploy

### Local Anvil (full stack + relay registration)

Start Anvil in a separate terminal, then run the deployment script with the optional
`DEPLOY_FAUCET` and `REGISTER_RELAY` flags. Both default to `false`.

```shell
anvil
```

```shell
# Use any Anvil pre-funded account private key
export DEPLOYER_PRIVATE_KEY=0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80

DEPLOY_FAUCET=true \
REGISTER_RELAY=true \
forge script script/DeployVayuCore.s.sol \
  --rpc-url http://127.0.0.1:8545 --broadcast -vvvv
```

The script prints a summary of all deployed addresses on completion:

```
=== Vayu Protocol Deployment ===
Deployer:             0xf39F...
VayuRewards:          0x5FbD...
VayuToken:            0xe7f1...
VayuEpochSettlement:  0x9fE4...
VayuFaucet:           0x2279...
Relay registered:     0xf39F...
```

Copy `VayuEpochSettlement` into the relay's `RELAY_CHAIN_SETTLEMENT_ADDRESS` env var.

### Environment variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `DEPLOYER_PRIVATE_KEY` | yes | — | Hex private key of the deployer wallet |
| `TREASURY` | no | deployer | Protocol treasury address (receives 20% VAYU) |
| `TEAM` | no | deployer | Team address (receives 10% VAYU) |
| `COMMUNITY` | no | deployer | Community address (receives 10% VAYU) |
| `DEPLOY_FAUCET` | no | `false` | Deploy `VayuFaucet` and seed it with 100k VAYU |
| `REGISTER_RELAY` | no | `false` | Approve `MIN_RELAY_STAKE` and call `registerRelay()` as deployer |

### Testnet / mainnet

Supply real addresses for `TREASURY`, `TEAM`, and `COMMUNITY` and add `--verify` for Etherscan verification:

```shell
DEPLOYER_PRIVATE_KEY=<pk> \
TREASURY=<treasury_addr> \
TEAM=<team_addr> \
COMMUNITY=<community_addr> \
forge script script/DeployVayuCore.s.sol \
  --rpc-url <rpc_url> --broadcast --verify -vvvv
```

## Useful cast commands

Verify the relay is active after registration:

```shell
cast call <settlement_addr> "isActiveRelay(address)(bool)" <relay_addr> --rpc-url http://127.0.0.1:8545
```

Inspect a committed epoch:

```shell
cast call <settlement_addr> \
  "getEpochCommitment(uint32)((bytes32,bytes32,string,address,uint64,uint32,uint32,bool,bool))" \
  <epoch_id> --rpc-url http://127.0.0.1:8545
```

Check VAYU balance:

```shell
cast call <token_addr> "balanceOf(address)(uint256)" <wallet_addr> --rpc-url http://127.0.0.1:8545
```

## Help

```shell
$ forge --help
$ anvil --help
$ cast --help
```
