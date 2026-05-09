# Vayu Protocol — Sequence Diagrams

All core protocol flows. Each diagram maps directly to the interfaces
in `contracts/src/interfaces/` and the relay API in `relay/openapi.yaml`.

---

## 1. Submit Reading

Edge device submits a signed AQI reading to the relay during an epoch.

```mermaid
sequenceDiagram
    participant D as Edge Device
    participant R as Relay Server
    participant C as Settlement Contract

    Note over D: Sensor reads PM2.5, computes AQI<br/>GPS → H3 cell (resolution 8)

    D->>D: Build AQIReading struct<br/>(reporter, h3Index, epochId,<br/>aqi, pm25, ..., timestamp)
    D->>D: EIP-712 sign(reading, devicePrivateKey)

    D->>R: POST /v1/readings<br/>{reading, signature}

    R->>R: 1. Schema validation (aqi > 0, pm25 > 0)
    R->>R: 2. ecrecover(eip712Hash, sig) == reporter?
    R->>C: 3. reporterStake(reporter) > 0?
    C-->>R: stake amount (cached)
    R->>R: 4. Timestamp within epoch ± 5 min?
    R->>R: 5. H3 resolution == 8?
    R->>R: 6. Rate limit: 1 per reporter per 5 min?

    alt All checks pass
        R->>R: Buffer reading for epoch aggregation
        R-->>D: 200 {status: "accepted", epochId}
    else Validation fails
        R-->>D: 400/401 {error: "reason"}
    end
```

---

## 2. Epoch Commit

At the end of each epoch (1 hour), the relay aggregates readings,
computes rewards, and commits on-chain.

```mermaid
sequenceDiagram
    participant R as Relay Server
    participant IPFS as IPFS Node
    participant S as Settlement Contract
    participant E as Rewards Escrow

    Note over R: Epoch window closes (e.g. epoch 500)

    R->>R: Group readings by H3 cell
    R->>R: Filter cells with < 3 reporters (inactive)

    loop For each active cell
        R->>R: Compute median AQI from cell readings
        R->>R: Score each reporter:<br/>score = 1 - |aqi - median| / tolerance
        R->>R: Compute cell reward budget:<br/>cellBudget = epochBudget / activeCells
        R->>R: Distribute cellBudget by score × stake weight
    end

    R->>R: Build DATA Merkle tree<br/>(leaves sorted by keccak256(reporter, h3Index))<br/>leaf = keccak256(reporter, h3, epoch, aqi, pm25, ..., ts)
    R->>R: Build REWARD Merkle tree<br/>(leaves sorted by reporter address)<br/>leaf = keccak256(reporter, epoch, cellId, amount)

    R->>R: Assemble epoch blob (JSON v1):<br/>{epochId, cells, readings, rewards, summary}
    R->>IPFS: Pin epoch blob
    IPFS-->>R: CID (content hash)

    R->>R: Identify penaltyList<br/>(reporters with ≥ 10 consecutive zero-score epochs)

    R->>S: commitEpoch(epochId, dataRoot, rewardRoot,<br/>cid, activeCells, totalReadings, penaltyList)

    S->>S: Verify msg.sender is registered relay<br/>with stake ≥ MIN_RELAY_STAKE
    S->>S: Store EpochCommitment<br/>(dataRoot, rewardRoot, cid, timestamp)
    S->>E: releaseEpochBudget(epochId)
    E-->>S: Transfer ~685 tokens to settlement

    opt penaltyList not empty
        loop For each penalized reporter
            S->>S: Slash 5% of reporter stake<br/>Transfer slash to treasury
            S->>S: emit Slashed(reporter, amount, 0, AUTO)
        end
    end

    S->>S: emit EpochCommitted(epochId, relay, ...)
    S-->>R: Transaction receipt

    Note over S: 12-hour challenge window starts
```

---

## 3a. Challenge — Spatial Anomaly

A fisherman detects that a cell's median AQI is inconsistent with its
neighbours, suggesting Sybil collusion within that cell.

```mermaid
sequenceDiagram
    participant F as Fisherman
    participant IPFS as IPFS Node
    participant S as Settlement Contract

    Note over F: Monitors committed epochs via indexer/IPFS

    F->>IPFS: Fetch epoch blob (CID from EpochCommitted event)
    IPFS-->>F: Epoch data (readings + rewards)

    F->>F: Recompute cell medians<br/>Detect: cell 882830a1 median = 400<br/>but all 6 neighbours median ≈ 80<br/>Δ > SPATIAL_TOLERANCE_AQI (50)

    F->>F: Build Merkle proofs for:<br/>• All readings in disputed cell<br/>• All readings in neighbour cells

    F->>S: challengeSpatialAnomaly(<br/>epochId, disputedCell,<br/>cellReadings, cellProofs,<br/>neighbourReadings, neighbourProofs)

    S->>S: Verify challenge window still open<br/>(block.timestamp < committedAt + 12h)

    loop For each reading proof
        S->>S: Verify Merkle proof against dataRoot
    end

    S->>S: Compute disputed cell median on-chain
    S->>S: Compute neighbour cells mean on-chain
    S->>S: Check |disputedMedian - neighbourMean| > SPATIAL_TOLERANCE_AQI

    alt Anomaly confirmed
        loop For each reporter in disputed cell
            S->>S: Slash SLASH_REPORTER_FISHERMAN (10%)<br/>of reporter's stake
        end
        S->>S: Pay fisherman reward<br/>(portion of total slashed amount)
        S->>S: emit Slashed(...) for each reporter
        S->>S: emit ChallengeResolved(epochId, F, SPATIAL, true)
        S-->>F: Success — fisherman receives reward
    else Anomaly not confirmed
        S->>S: emit ChallengeResolved(epochId, F, SPATIAL, false)
        S-->>F: Challenge failed — fisherman pays only gas
    end
```

---

## 3b. Challenge — Reward Computation

A fisherman detects the relay computed rewards incorrectly for a cell.

```mermaid
sequenceDiagram
    participant F as Fisherman
    participant IPFS as IPFS Node
    participant S as Settlement Contract

    F->>IPFS: Fetch epoch blob
    IPFS-->>F: Epoch data

    F->>F: Recompute scores and rewards for cell<br/>Compare against relay's reward manifest<br/>Detect discrepancy in reward amounts

    F->>F: Extract disputed cell readings + proofs<br/>Extract relay's claimed reward allocations

    F->>S: challengeRewardComputation(<br/>epochId, disputedCell,<br/>cellReadings, cellProofs,<br/>claimedReporters, claimedAmounts)

    S->>S: Verify challenge window open

    loop For each reading proof
        S->>S: Verify Merkle proof against dataRoot
    end

    S->>S: Recompute on-chain:<br/>1. Median AQI for the cell<br/>2. Score per reporter<br/>3. Correct reward amounts
    S->>S: Compare computed rewards vs claimedAmounts

    alt Rewards were wrong
        S->>S: Slash relay SLASH_RELAY_REWARD_COMPUTATION<br/>(e.g., 5% of relay stake)
        S->>S: Store correctedRoot for this epoch
        S->>S: emit RewardRootCorrected(epochId, correctedRoot)
        S->>S: Pay fisherman reward
        S->>S: emit ChallengeResolved(epochId, F, REWARD, true)
        S-->>F: Success
        Note over S: Reporters claim using correctedRoot
    else Rewards were correct
        S->>S: emit ChallengeResolved(epochId, F, REWARD, false)
        S-->>F: Challenge failed
    end
```

---

## 3c. Challenge — Duplicate Location

A fisherman detects a reporter submitted readings from two physically
distant cells in the same epoch (impossible without GPS spoofing).

```mermaid
sequenceDiagram
    participant F as Fisherman
    participant IPFS as IPFS Node
    participant S as Settlement Contract

    F->>IPFS: Fetch epoch blob
    IPFS-->>F: Epoch data

    F->>F: Detect: reporter 0xABC submitted readings<br/>in cell A and cell B in same epoch<br/>H3 distance(A, B) > MAX_H3_TRAVEL_DISTANCE

    F->>F: Extract both readings + Merkle proofs

    F->>S: challengeDuplicateLocation(<br/>epochId, reading1, proof1,<br/>reading2, proof2)

    S->>S: Verify challenge window open
    S->>S: Verify proof1 against dataRoot
    S->>S: Verify proof2 against dataRoot
    S->>S: Confirm reading1.reporter == reading2.reporter
    S->>S: Confirm reading1.epochId == reading2.epochId
    S->>S: Compute h3Distance(h3Index1, h3Index2)
    S->>S: Check distance > MAX_H3_TRAVEL_DISTANCE

    alt Duplicate confirmed
        S->>S: Slash reporter SLASH_REPORTER_DUPLICATE_LOCATION
        S->>S: Pay fisherman reward
        S->>S: emit ChallengeResolved(epochId, F, DUPLICATE, true)
        S-->>F: Success
    else Not a duplicate / within range
        S->>S: emit ChallengeResolved(epochId, F, DUPLICATE, false)
        S-->>F: Challenge failed
    end
```

---

## 4. Reward Claim

After the challenge window closes, a reporter claims their epoch
reward using a Merkle proof.

```mermaid
sequenceDiagram
    participant U as Reporter / Wallet
    participant I as Indexer
    participant S as Settlement Contract
    participant T as VAYU Token

    Note over U: Challenge window closed<br/>(12h after epoch committed)

    U->>I: GET /v1/epochs/{epochId}/proofs/{reporter}
    I-->>U: {h3Index, amount, proof: [bytes32...]}

    U->>S: claimReward(epochId, h3Index, amount, proof)

    S->>S: Check: block.timestamp ≥ committedAt + CHALLENGE_WINDOW?
    S->>S: Check: block.timestamp ≤ committedAt + CLAIM_EXPIRY (90 days)?
    S->>S: Check: leaf not already claimed (bitmap)?
    S->>S: Compute leaf = keccak256(reporter, epochId, h3Index, amount)
    S->>S: Verify Merkle proof against rewardRoot<br/>(or correctedRoot if challenge succeeded)

    alt Proof valid and unclaimed
        S->>S: Mark leaf as claimed in bitmap
        S->>T: transfer(reporter, amount)
        T-->>U: VAYU tokens received
        S->>S: emit RewardClaimed(epochId, reporter, h3Index, amount)
        S-->>U: Success
    else Invalid proof / already claimed / expired
        S-->>U: Revert with reason
    end
```

---

## 5. Reward Expiry & Sweep

After the 90-day claim window expires, unclaimed rewards are returned
to the protocol treasury. Anyone can trigger this.

```mermaid
sequenceDiagram
    participant A as Anyone
    participant S as Settlement Contract
    participant T as VAYU Token
    participant Tr as Treasury (Multisig)

    Note over A: 90 days after epoch commit

    A->>S: sweepExpired(epochId)

    S->>S: Check: block.timestamp > committedAt + CLAIM_EXPIRY?
    S->>S: Check: epoch not already swept?

    alt Eligible for sweep
        S->>S: Calculate remaining unclaimed balance
        S->>S: Mark epoch as swept
        S->>T: transfer(treasury, remainingBalance)
        T-->>Tr: Unclaimed tokens returned
        S->>S: emit EpochSwept(epochId, amount)
        S-->>A: Success
    else Not expired or already swept
        S-->>A: Revert
    end
```

---

## 6. Reporter Onboarding (Stake Flow)

End-to-end flow from a new user acquiring tokens to activating a device.

```mermaid
sequenceDiagram
    participant U as User (Browser Wallet)
    participant F as Faucet Contract
    participant T as VAYU Token
    participant S as Settlement Contract
    participant D as Edge Device

    Note over U: New reporter — has no VAYU tokens

    U->>F: drip()
    F->>F: Check: lastDrip[user] + 24h elapsed?
    F->>T: transfer(user, 500 VAYU)
    T-->>U: 500 VAYU received

    D->>D: First boot: generate secp256k1 keypair<br/>deviceAddress = 0xABC...DEF
    D-->>U: Device address (QR code / BLE)

    U->>T: approve(settlementContract, 200 VAYU)
    U->>S: stakeFor(deviceAddress, 200 VAYU)
    S->>T: transferFrom(user, settlement, 200)
    S->>S: reporterStakes[deviceAddress] = 200
    S->>S: emit Staked(user, deviceAddress, 200)

    Note over D: Device is now active —<br/>relay will accept signed readings

    D->>D: Read sensor → build AQIReading → sign
    D->>R: POST /v1/readings {reading, signature}

    participant R as Relay Server
    R->>R: ecrecover → 0xABC (matches reporter)
    R->>S: reporterStake(0xABC) → 200 ✓
    R-->>D: 200 OK — accepted
```

---

## Lifecycle Overview

How the flows connect across the full epoch lifecycle.

```mermaid
sequenceDiagram
    participant D as Device
    participant R as Relay
    participant IPFS
    participant I as Indexer
    participant S as Contract
    participant F as Fisherman
    participant U as Reporter

    rect rgba(100, 149, 237, 0.05)
        Note right of D: EPOCH WINDOW (1 hour)
        loop Every 5 minutes
            D->>R: Submit signed reading
            R-->>D: Accepted
        end
    end

    rect rgba(76, 175, 80, 0.05)
        Note right of R: EPOCH CLOSE
        R->>R: Aggregate, score, build trees
        R->>IPFS: Pin epoch blob
        R->>S: commitEpoch(...)
        S->>S: Store roots, release budget
    end

    rect rgba(255, 152, 0, 0.05)
        Note right of F: CHALLENGE WINDOW (12 hours)
        F->>IPFS: Fetch & verify epoch data
        opt Fraud detected
            F->>S: challengeXxx(...)
            S->>S: Verify, slash, reward fisherman
        end
    end

    rect rgba(123, 104, 238, 0.05)
        Note right of U: CLAIM WINDOW (12h → 90 days)
        U->>I: Get Merkle proof
        U->>S: claimReward(...)
        S->>U: Transfer VAYU tokens
    end

    rect rgba(239, 83, 80, 0.05)
        Note right of S: AFTER 90 DAYS
        S->>S: sweepExpired() → treasury
    end
```
