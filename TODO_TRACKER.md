# Vayu Protocol TODO Tracker

## Phase 2b (Relay Signature Hardening)
- Persist replay index for duplicate signature digest detection with bounded retention. In-memory LRU/TTL map keyed on digest hex; bound size by epoch count × max reporters per epoch.
- Add domain rotation strategy (current + grace domain support). Triggered by `verifyingContract` address change on contract upgrade; try current domain, fall back to grace domain during overlap window.— `RelayMetrics` exposes tagged counters for `invalid_signature`, `duplicate`, `rate_limited`, `no_stake`, `validation_error`, `stale_timestamp`, `wrong_epoch`, and `wrong_resolution`.
- Add performance benchmarks for signature verification under load. JMH benchmark on `Eip712SignatureVerifier.verify()` using payloads from `reporter-simulator/simulate.py --output-fixtures`.

## Phase 3 (Relay Stake Check)
- Add RPC timeout, retry, and circuit-breaker behavior. Resilience4j `CircuitBreaker` + `Retry` wrapping the web3j call in `Web3jStakeWeightProvider`; expose timeouts via `relay.chain.*` config.
- Decide fail-open/fail-closed policy for transient RPC outages. Fail-open preserves liveness; fail-closed preserves stake integrity — document chosen policy explicitly in application.yml comments.
- Add cache strategy for stake reads (TTL and invalidation approach). Caffeine cache with short TTL (e.g. 5 min); invalidate on `StakeDeposited`/`StakeWithdrawn` events if event polling is added later.
- Grouping of reporter addresses and getting staking information in batch.

## Relay Engineering TODOs
- Move invalid signature and no-stake error messages into configurable message templates.

## Repo-wide Documentation TODOs
- Update root README with end-to-end architecture and service boundaries.
- Update relay README with Phase 2a EIP-712 behavior and security toggles.
- Update contracts README with reporter stake/check interaction points used by relay.
- Update docs sequence diagrams to include signature domain details and replay strategy.
- Update indexer and dashboard docs to reflect write-path validation behavior and error semantics.
- Add a changelog section documenting package refactors under relay service ingestion modules.
