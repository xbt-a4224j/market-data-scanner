-- V3: Admin-panel labeling audit trail + deployer-reputation tracking

CREATE TABLE review_decisions (
    id              BIGSERIAL PRIMARY KEY,
    pool_id         BIGINT       NOT NULL REFERENCES pool_detections(id),
    reviewer        VARCHAR(64)  NOT NULL,
    label           VARCHAR(20)  NOT NULL,
    notes           TEXT,
    reviewed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_label CHECK (label IN ('legitimate', 'scam', 'uncertain', 'deferred'))
);

CREATE INDEX idx_review_decisions_pool ON review_decisions(pool_id);
CREATE INDEX idx_review_decisions_reviewer ON review_decisions(reviewer);
CREATE INDEX idx_review_decisions_label ON review_decisions(label);


-- Deployer reputation table — populated by review decisions marking 'scam' on deployers
CREATE TABLE deployer_reputation (
    address                 VARCHAR(42)  PRIMARY KEY,
    scam_confirmations      INTEGER      NOT NULL DEFAULT 0,
    legitimate_associations INTEGER      NOT NULL DEFAULT 0,
    first_seen              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_updated            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    notes                   TEXT
);

CREATE INDEX idx_deployer_reputation_scam ON deployer_reputation(scam_confirmations DESC);
