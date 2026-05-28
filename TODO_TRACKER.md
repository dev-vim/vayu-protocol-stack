# Vayu Protocol TODO Tracker

## Phase 2b (Relay Signature Hardening)
- ~~Add replay protection for signed readings (nonce or signed epoch/window). Simplest: treat `(reporter, epochId)` as replay key — one accepted reading per reporter per epoch; no external store needed.~~ **Done** — `InMemoryEpochIngressWindow.tryClaimReplayKey()` claims a `reporter:epochId:h3Index` key atomically; the set is cleared per epoch on drain, providing bounded in-process replay protection.
- Persist replay index for duplicate signature digest detection with bounded retention. In-memory LRU/TTL map keyed on digest hex; bound size by epoch count × max reporters per epoch.
- ~~Enforce low-s signature policy and strict signature malleability rules. Reject where `s > secp256k1_n/2` before ecrecover; web3j exposes `ECDSASignature.isCanonical()`.~~ **Done** — `Eip712SignatureVerifier` checks `s.compareTo(SECP256K1_HALF_N) > 0` and returns `false` before attempting key recovery.
- Add domain rotation strategy (current + grace domain support). Triggered by `verifyingContract` address change on contract upgrade; try current domain, fall back to grace domain during overlap window.
- ~~Add signature failure metrics and structured reason codes. Extend existing `vayu.readings.rejected` counter with per-reason tags: `MALFORMED`, `REPLAY` (in addition to existing `invalid_signature`, `no_stake`, `rate_limited`, `duplicate`).~~ **Done** — `RelayMetrics` exposes tagged counters for `invalid_signature`, `duplicate`, `rate_limited`, `no_stake`, `validation_error`, `stale_timestamp`, `wrong_epoch`, and `wrong_resolution`.
- Add performance benchmarks for signature verification under load. JMH benchmark on `Eip712SignatureVerifier.verify()` using payloads from `reporter-simulator/simulate.py --output-fixtures`.

## Phase 3 (Relay Stake Check)
- ~~Implement on-chain reporter stake check against settlement contract.~~ **Done** — `Web3jStakeWeightProvider` calls `reporterStake(address)` via `eth_call`; activated via `relay.security.stake-check-enabled=true`.
- Add RPC timeout, retry, and circuit-breaker behavior. Resilience4j `CircuitBreaker` + `Retry` wrapping the web3j call in `Web3jStakeWeightProvider`; expose timeouts via `relay.chain.*` config.
- Decide fail-open/fail-closed policy for transient RPC outages. Fail-open preserves liveness; fail-closed preserves stake integrity — document chosen policy explicitly in application.yml comments.
- Add cache strategy for stake reads (TTL and invalidation approach). Caffeine cache with short TTL (e.g. 5 min); invalidate on `StakeDeposited`/`StakeWithdrawn` events if event polling is added later.
- Grouping of reporter addresses and getting staking information in batch.

## Relay Engineering TODOs
- Move invalid signature and no-stake error messages into configurable message templates.
- ~~Add dedicated `@SpringBootTest` integration test with signature verification enabled. `@ActiveProfiles("sig-enabled")` with `RELAY_SECURITY_SIGNATURE_VERIFICATION_ENABLED=true`; use `TestEip712Signer` (already exists) to generate valid and tampered payloads inline — no external fixture files needed.~~ **Done** — `RelayControllerSigEnabledTest` is a full `@SpringBootTest` / `@AutoConfigureMockMvc` / `@ActiveProfiles("sig-enabled")` suite covering valid sig, tampered sig, wrong signer, rate-limit poisoning prevention, and repeated-submission rate-limit enforcement.
- ~~Add observability: counters for accepted, invalid signature, no stake, rate-limited, and validation failures.~~ **Done** — `RelayMetrics.java` implements `vayu.readings.accepted` and `vayu.readings.rejected` (tagged by reason) with full test coverage in `RelayMetricsTest`.
- ~~Build reporter-simulator as a Python script using `eth_account`.~~ **Done** — `reporter-simulator/simulate.py` signs EIP-712 readings, supports `--dry-run`, `--output-fixtures`, `--tamper`, and multi-round submission.

## Indexer TODOs
- ~~Fix `reporters.totalReadings` and `reporters.totalRewards` — the sidecar `processor.ts` populates `cell_epochs` and `readings` tables but never updates these reporter-level aggregate columns; they remain 0 for all reporters.~~ **Done** — `markIngested()` now runs inside a transaction with an `ipfs_status != 'INGESTED'` idempotency guard; reporter reading counts and reward totals are incremented atomically with the epoch status flip.
- ~~Add API endpoint for raw per-reporter readings. `GET /reporters/:address/readings` or `GET /epochs/:epochId/readings` backed by the existing `readings` table populated by the sidecar.~~ **Done** — both `GET /reporters/:address/readings` (with optional `limit`, max 500) and `GET /epochs/:epochId/readings` added to `indexer/src/api/index.ts`.
- ~~Uncomment Base Sepolia and Base mainnet chain configs in `ponder.config.ts` for testnet/mainnet deployment readiness.~~ **Done** — all three chains defined; active chain selected via `PONDER_CHAIN` env var (default `anvil`); contract `chain` field follows the env var.

## Dashboard TODOs
- Add epoch detail page — click an epoch row to see its cell breakdown and reporter list.
- Add reporter detail page — stake history, per-epoch earnings, slash events.
- Add challenges/disputes view — surface `ChallengeSubmitted`, `ChallengeResolved`, and `Slashed` events.

## Repo-wide Documentation TODOs
- Update root README with end-to-end architecture and service boundaries.
- Update relay README with Phase 2a EIP-712 behavior and security toggles.
- Update contracts README with reporter stake/check interaction points used by relay.
- Update docs sequence diagrams to include signature domain details and replay strategy.
- Update indexer and dashboard docs to reflect write-path validation behavior and error semantics.
- Add a changelog section documenting package refactors under relay service ingestion modules.
