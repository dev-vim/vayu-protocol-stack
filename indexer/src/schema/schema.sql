-- ═══════════════════════════════════════════════════════════════════════════════
-- Vayu Protocol — Indexer PostgreSQL Schema
-- ═══════════════════════════════════════════════════════════════════════════════
--
-- This schema is the single source of truth for the indexer database.
-- It ingests data from two sources:
--   1. On-chain events (EpochCommitted, RewardClaimed, Slashed, Staked, etc.)
--   2. Off-chain IPFS blobs (individual readings, per-cell aggregates, rewards)
--
-- The indexer downloads each epoch's IPFS blob after seeing EpochCommitted,
-- unpacks the readings and rewards, and populates the off-chain tables.
--
-- Conventions:
--   - Ethereum addresses are stored as CHAR(42) with 0x prefix (checksummed)
--   - Token amounts are NUMERIC(78,0) — uint256 in wei, no decimals
--   - H3 cell indexes are stored as BIGINT (uint64)
--   - bytes32 hashes are CHAR(66) with 0x prefix
--   - All timestamps are INTEGER (UNIX seconds) for chain-native values,
--     TIMESTAMPTZ for indexer-generated metadata
--   - On-chain sourced columns are marked with -- [on-chain]
--   - Off-chain (IPFS) sourced columns are marked with -- [off-chain]
--
-- ═══════════════════════════════════════════════════════════════════════════════


-- ─────────────────────────────────────────────────────────────────────────────
-- EPOCHS
-- Source: EpochCommitted event + IPFS blob metadata
-- One row per committed epoch. This is the indexer's primary sync cursor.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE epochs (
    epoch_id          INTEGER       PRIMARY KEY,                    -- [on-chain]
    relay             CHAR(42)      NOT NULL,                       -- [on-chain] relay that committed
    data_root         CHAR(66)      NOT NULL,                       -- [on-chain] Merkle root of data tree
    reward_root       CHAR(66)      NOT NULL,                       -- [on-chain] Merkle root of reward tree
    ipfs_cid          TEXT          NOT NULL,                       -- [on-chain] IPFS CID of epoch blob
    active_cells      INTEGER       NOT NULL,                       -- [on-chain] cells with >= 3 reporters
    total_readings    INTEGER       NOT NULL,                       -- [on-chain] total readings across all cells
    total_reward      NUMERIC(78,0) NOT NULL DEFAULT 0,             -- [off-chain] sum of all rewards in this epoch (wei)
    committed_at      INTEGER       NOT NULL,                       -- [on-chain] block.timestamp
    block_number      BIGINT        NOT NULL,                       -- [on-chain] block number
    tx_hash           CHAR(66)      NOT NULL,                       -- [on-chain] commitEpoch tx hash
    challenge_window_end INTEGER    NOT NULL,                       -- computed: committed_at + 43200 (12h)
    swept             BOOLEAN       NOT NULL DEFAULT FALSE,         -- set TRUE after sweepExpired called
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW()          -- indexer metadata
);

CREATE INDEX idx_epochs_relay ON epochs (relay);
CREATE INDEX idx_epochs_committed_at ON epochs (committed_at);


-- ─────────────────────────────────────────────────────────────────────────────
-- CELL_EPOCHS
-- Source: IPFS blob (per-cell aggregates computed by relay)
-- One row per active cell per epoch.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE cell_epochs (
    id                BIGSERIAL     PRIMARY KEY,
    epoch_id          INTEGER       NOT NULL REFERENCES epochs(epoch_id),    -- [off-chain]
    cell_id           BIGINT        NOT NULL,                                -- [off-chain] H3 index
    median_aqi        SMALLINT      NOT NULL,                                -- [off-chain]
    reporter_count    SMALLINT      NOT NULL,                                -- [off-chain]
    cell_budget       NUMERIC(78,0) NOT NULL DEFAULT 0,                      -- [off-chain] tokens allocated (wei)
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_cell_epoch UNIQUE (epoch_id, cell_id)
);

CREATE INDEX idx_cell_epochs_cell ON cell_epochs (cell_id);
CREATE INDEX idx_cell_epochs_epoch ON cell_epochs (epoch_id);


-- ─────────────────────────────────────────────────────────────────────────────
-- READINGS
-- Source: IPFS blob (individual readings from epoch data)
-- One row per reporter per cell per epoch.
-- This is the largest table — grows by (reporters × epochs) rows.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE readings (
    id                BIGSERIAL     PRIMARY KEY,
    epoch_id          INTEGER       NOT NULL REFERENCES epochs(epoch_id),    -- [off-chain]
    cell_id           BIGINT        NOT NULL,                                -- [off-chain] H3 index
    reporter          CHAR(42)      NOT NULL,                                -- [off-chain]
    aqi               SMALLINT      NOT NULL,                                -- [off-chain] 0-500
    pm25              SMALLINT      NOT NULL,                                -- [off-chain] µg/m³ × 10
    pm10              SMALLINT      NOT NULL DEFAULT 0,                      -- [off-chain] 0 = not measured
    o3                SMALLINT      NOT NULL DEFAULT 0,                      -- [off-chain]
    no2               SMALLINT      NOT NULL DEFAULT 0,                      -- [off-chain]
    so2               SMALLINT      NOT NULL DEFAULT 0,                      -- [off-chain]
    co                SMALLINT      NOT NULL DEFAULT 0,                      -- [off-chain]
    timestamp         INTEGER       NOT NULL,                                -- [off-chain] UNIX time of reading
    score             NUMERIC(10,6),                                         -- [off-chain] Schelling score (0.0-1.0)
    reward_amount     NUMERIC(78,0) DEFAULT 0,                               -- [off-chain] tokens earned (wei)
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_reading UNIQUE (epoch_id, cell_id, reporter)
);

CREATE INDEX idx_readings_epoch ON readings (epoch_id);
CREATE INDEX idx_readings_reporter ON readings (reporter);
CREATE INDEX idx_readings_cell ON readings (cell_id);
CREATE INDEX idx_readings_epoch_cell ON readings (epoch_id, cell_id);
CREATE INDEX idx_readings_reporter_epoch ON readings (reporter, epoch_id);


-- ─────────────────────────────────────────────────────────────────────────────
-- REPORTERS
-- Source: Staked/UnstakeInitiated/Withdrawn events + aggregated reading data
-- One row per unique reporter address. Updated incrementally.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE reporters (
    address             CHAR(42)      PRIMARY KEY,                       -- [on-chain]
    staker              CHAR(42),                                        -- [on-chain] who called stakeFor()
    stake               NUMERIC(78,0) NOT NULL DEFAULT 0,                -- [on-chain] active stake (wei)
    pending_unstake     NUMERIC(78,0) NOT NULL DEFAULT 0,                -- [on-chain] in cooldown
    withdrawable_at     INTEGER,                                         -- [on-chain] UNIX time
    total_readings      INTEGER       NOT NULL DEFAULT 0,                -- aggregated
    total_rewards       NUMERIC(78,0) NOT NULL DEFAULT 0,                -- aggregated (wei)
    total_claimed       NUMERIC(78,0) NOT NULL DEFAULT 0,                -- aggregated (wei)
    avg_score           NUMERIC(10,6) NOT NULL DEFAULT 0,                -- rolling average
    consecutive_zeros   INTEGER       NOT NULL DEFAULT 0,                -- current streak
    reporter_type       SMALLINT      NOT NULL DEFAULT 0,                -- 0=unknown, 1=individual, 2=low-cost, 3=reference
    first_seen_epoch    INTEGER,                                         -- first reading epoch
    last_seen_epoch     INTEGER,                                         -- most recent reading epoch
    is_slashed          BOOLEAN       NOT NULL DEFAULT FALSE,            -- has been slashed at least once
    total_slashed       NUMERIC(78,0) NOT NULL DEFAULT 0,                -- cumulative slash amount (wei)
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_reporters_stake ON reporters (stake) WHERE stake > 0;
CREATE INDEX idx_reporters_last_seen ON reporters (last_seen_epoch);


-- ─────────────────────────────────────────────────────────────────────────────
-- RELAYS
-- Source: RelayRegistered/RelayDeactivated/Slashed events
-- One row per relay address.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE relays (
    address             CHAR(42)      PRIMARY KEY,                       -- [on-chain]
    stake               NUMERIC(78,0) NOT NULL DEFAULT 0,                -- [on-chain]
    pending_unstake     NUMERIC(78,0) NOT NULL DEFAULT 0,                -- [on-chain]
    withdrawable_at     INTEGER,                                         -- [on-chain]
    is_active           BOOLEAN       NOT NULL DEFAULT TRUE,             -- [on-chain]
    epochs_committed    INTEGER       NOT NULL DEFAULT 0,                -- aggregated
    total_fees_earned   NUMERIC(78,0) NOT NULL DEFAULT 0,                -- aggregated (wei)
    total_slashed       NUMERIC(78,0) NOT NULL DEFAULT 0,                -- aggregated (wei)
    registered_at       INTEGER,                                         -- [on-chain] block.timestamp
    registered_block    BIGINT,                                          -- [on-chain]
    registered_tx       CHAR(66),                                        -- [on-chain]
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);


-- ─────────────────────────────────────────────────────────────────────────────
-- CLAIMS
-- Source: RewardClaimed event
-- One row per claim transaction.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE claims (
    id                BIGSERIAL     PRIMARY KEY,
    epoch_id          INTEGER       NOT NULL REFERENCES epochs(epoch_id),    -- [on-chain]
    reporter          CHAR(42)      NOT NULL,                                -- [on-chain]
    cell_id           BIGINT        NOT NULL,                                -- [on-chain] H3 index
    amount            NUMERIC(78,0) NOT NULL,                                -- [on-chain] (wei)
    block_number      BIGINT        NOT NULL,                                -- [on-chain]
    tx_hash           CHAR(66)      NOT NULL,                                -- [on-chain]
    claimed_at        INTEGER       NOT NULL,                                -- [on-chain] block.timestamp

    CONSTRAINT uq_claim UNIQUE (epoch_id, reporter, cell_id)
);

CREATE INDEX idx_claims_reporter ON claims (reporter);
CREATE INDEX idx_claims_epoch ON claims (epoch_id);


-- ─────────────────────────────────────────────────────────────────────────────
-- CHALLENGES
-- Source: ChallengeSubmitted + ChallengeResolved + Slashed events
-- One row per challenge attempt (successful or not).
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE challenges (
    id                BIGSERIAL     PRIMARY KEY,
    epoch_id          INTEGER       NOT NULL REFERENCES epochs(epoch_id),    -- [on-chain]
    challenge_type    SMALLINT      NOT NULL,                                -- [on-chain] 0=spatial, 1=reward, 2=data, 3=duplicate
    challenger        CHAR(42)      NOT NULL,                                -- [on-chain] fisherman address
    target            CHAR(42),                                              -- [on-chain] slashed party (NULL if challenge failed)
    cell_id           BIGINT,                                                -- [on-chain] disputed cell (NULL for DuplicateLocation)
    succeeded         BOOLEAN       NOT NULL,                                -- [on-chain]
    slash_amount      NUMERIC(78,0) DEFAULT 0,                               -- [on-chain] (wei, 0 if failed)
    fisherman_reward  NUMERIC(78,0) DEFAULT 0,                               -- [on-chain] (wei, 0 if failed)
    block_number      BIGINT        NOT NULL,                                -- [on-chain]
    tx_hash           CHAR(66)      NOT NULL,                                -- [on-chain]
    challenged_at     INTEGER       NOT NULL,                                -- [on-chain] block.timestamp
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_challenges_epoch ON challenges (epoch_id);
CREATE INDEX idx_challenges_challenger ON challenges (challenger);
CREATE INDEX idx_challenges_target ON challenges (target);
CREATE INDEX idx_challenges_type ON challenges (challenge_type);


-- ─────────────────────────────────────────────────────────────────────────────
-- STAKE_EVENTS
-- Source: Staked / UnstakeInitiated / Withdrawn events
-- Immutable append-only log of all stake lifecycle events.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE stake_events (
    id                BIGSERIAL     PRIMARY KEY,
    account           CHAR(42)      NOT NULL,                                -- [on-chain] reporter or relay address
    staker            CHAR(42),                                              -- [on-chain] who sent the tx (for stakeFor)
    event_type        SMALLINT      NOT NULL,                                -- 0=staked, 1=unstake_initiated, 2=withdrawn
    amount            NUMERIC(78,0) NOT NULL,                                -- [on-chain] (wei)
    withdrawable_at   INTEGER,                                               -- [on-chain] only for unstake_initiated
    block_number      BIGINT        NOT NULL,                                -- [on-chain]
    tx_hash           CHAR(66)      NOT NULL,                                -- [on-chain]
    event_at          INTEGER       NOT NULL,                                -- [on-chain] block.timestamp
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_stake_events_account ON stake_events (account);
CREATE INDEX idx_stake_events_type ON stake_events (event_type);


-- ─────────────────────────────────────────────────────────────────────────────
-- EPOCH_SWEEPS
-- Source: EpochSwept event
-- One row per sweep. Tracks tokens returned to treasury from expired claims.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE epoch_sweeps (
    id                BIGSERIAL     PRIMARY KEY,
    epoch_id          INTEGER       NOT NULL REFERENCES epochs(epoch_id),    -- [on-chain]
    amount            NUMERIC(78,0) NOT NULL,                                -- [on-chain] (wei)
    block_number      BIGINT        NOT NULL,                                -- [on-chain]
    tx_hash           CHAR(66)      NOT NULL,                                -- [on-chain]
    swept_at          INTEGER       NOT NULL,                                -- [on-chain] block.timestamp
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_epoch_sweep UNIQUE (epoch_id)
);


-- ─────────────────────────────────────────────────────────────────────────────
-- INDEXER SYNC CURSOR
-- Tracks the last block processed by the indexer for crash recovery.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE sync_state (
    id                  INTEGER       PRIMARY KEY DEFAULT 1 CHECK (id = 1),  -- singleton row
    last_block_number   BIGINT        NOT NULL DEFAULT 0,
    last_block_hash     CHAR(66),
    last_epoch_ingested INTEGER       DEFAULT 0,
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

INSERT INTO sync_state (last_block_number) VALUES (0);
