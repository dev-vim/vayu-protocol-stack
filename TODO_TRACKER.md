# Vayu Protocol TODO Tracker

## Phase 2b (Relay Signature Hardening)
- Add replay protection for signed readings (nonce or signed epoch/window).
- Persist replay index for duplicate signature digest detection with bounded retention.
- Enforce low-s signature policy and strict signature malleability rules.
- Add domain rotation strategy (current + grace domain support).
- Add signature failure metrics and structured reason codes.
- Add performance benchmarks for signature verification under load.

## Phase 3 (Relay Stake Check)
- Implement on-chain reporter stake check against settlement contract.
- Add RPC timeout, retry, and circuit-breaker behavior.
- Decide fail-open/fail-closed policy for transient RPC outages.
- Add cache strategy for stake reads (TTL and invalidation approach).

## Relay Engineering TODOs
- Move invalid signature and no-stake error messages into configurable message templates.
- Add integration tests for signature verification with golden vectors from an external signer toolchain.
- Add dedicated test profile with signature verification enabled in Spring context tests.
- Add observability: counters for accepted, invalid signature, no stake, rate-limited, and validation failures.

## Repo-wide Documentation TODOs
- Update root README with end-to-end architecture and service boundaries.
- Update relay README with Phase 2a EIP-712 behavior and security toggles.
- Update contracts README with reporter stake/check interaction points used by relay.
- Update docs sequence diagrams to include signature domain details and replay strategy.
- Update indexer and dashboard docs to reflect write-path validation behavior and error semantics.
- Add a changelog section documenting package refactors under relay service ingestion modules.
