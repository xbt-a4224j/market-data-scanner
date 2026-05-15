-- V1: Core ingestion ledger + pool detection schema
-- Sets up reorg-aware idempotent ingestion via the (block_number, block_hash) PK on processed_blocks.

CREATE TABLE ingestion_runs (
    id                          BIGSERIAL PRIMARY KEY,
    mode                        VARCHAR(20)  NOT NULL,
    started_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ended_at                    TIMESTAMPTZ,
    from_block                  BIGINT       NOT NULL,
    to_block                    BIGINT,
    last_processed_block        BIGINT,
    last_processed_block_hash   VARCHAR(66),
    pools_detected              INTEGER      NOT NULL DEFAULT 0,
    status                      VARCHAR(20)  NOT NULL,
    error_message               TEXT,
    created_by                  VARCHAR(64)  NOT NULL DEFAULT 'system',

    CONSTRAINT chk_mode CHECK (mode IN ('live', 'backfill')),
    CONSTRAINT chk_status CHECK (status IN ('running', 'completed', 'failed', 'reorged'))
);

CREATE INDEX idx_ingestion_runs_block_range ON ingestion_runs(from_block, to_block);
CREATE INDEX idx_ingestion_runs_status ON ingestion_runs(status);
CREATE INDEX idx_ingestion_runs_mode ON ingestion_runs(mode);


-- Per-block ledger with compound (number, hash) PK — load-bearing for idempotency + reorg detection
CREATE TABLE processed_blocks (
    block_number       BIGINT       NOT NULL,
    block_hash         VARCHAR(66)  NOT NULL,
    block_timestamp    TIMESTAMPTZ  NOT NULL,
    ingestion_run_id   BIGINT       NOT NULL REFERENCES ingestion_runs(id),
    pool_count         INTEGER      NOT NULL DEFAULT 0,
    processed_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status             VARCHAR(20)  NOT NULL DEFAULT 'canonical',  -- 'canonical' | 'reorged'

    PRIMARY KEY (block_number, block_hash),
    CONSTRAINT chk_block_status CHECK (status IN ('canonical', 'reorged'))
);

CREATE INDEX idx_processed_blocks_number ON processed_blocks(block_number);
CREATE INDEX idx_processed_blocks_timestamp ON processed_blocks(block_timestamp);
CREATE INDEX idx_processed_blocks_run ON processed_blocks(ingestion_run_id);


-- Per-pool detection record
CREATE TABLE pool_detections (
    id                  BIGSERIAL PRIMARY KEY,
    tx_hash             VARCHAR(66)  NOT NULL,
    pool_address        VARCHAR(42)  NOT NULL,
    token_address       VARCHAR(42)  NOT NULL,
    deployer_address    VARCHAR(42)  NOT NULL,
    paired_with         VARCHAR(42)  NOT NULL,
    fee_tier            INTEGER      NOT NULL,
    block_number        BIGINT       NOT NULL,
    block_hash          VARCHAR(66)  NOT NULL,
    block_timestamp     TIMESTAMPTZ  NOT NULL,
    detected_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ingestion_run_id    BIGINT       NOT NULL REFERENCES ingestion_runs(id),

    -- Heuristic outputs
    composite_score     INTEGER      NOT NULL,
    heuristic_versions  JSONB        NOT NULL,                    -- {heuristic_name: version, ...}
    heuristic_results   JSONB        NOT NULL,                    -- per-heuristic full results
    flagged_signals     TEXT[]       NOT NULL DEFAULT ARRAY[]::TEXT[],

    status              VARCHAR(20)  NOT NULL DEFAULT 'detected', -- 'detected' | 'reorged' | 'reviewed'

    CONSTRAINT uq_pool_per_block UNIQUE (pool_address, block_hash),  -- idempotent reinsertion
    CONSTRAINT chk_pool_status CHECK (status IN ('detected', 'reorged', 'reviewed'))
);

CREATE INDEX idx_pool_detections_detected_at ON pool_detections(detected_at DESC);
CREATE INDEX idx_pool_detections_score ON pool_detections(composite_score DESC);
CREATE INDEX idx_pool_detections_deployer ON pool_detections(deployer_address);
CREATE INDEX idx_pool_detections_token ON pool_detections(token_address);
CREATE INDEX idx_pool_detections_status ON pool_detections(status);
CREATE INDEX idx_pool_detections_run ON pool_detections(ingestion_run_id);


-- Singleton watermark row tracking the contiguous-processed range
CREATE TABLE ingestion_state (
    id                          INTEGER      PRIMARY KEY CHECK (id = 1),
    earliest_processed_block    BIGINT,
    latest_processed_block      BIGINT,
    latest_processed_block_hash VARCHAR(66),
    last_updated                TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO ingestion_state (id) VALUES (1);
