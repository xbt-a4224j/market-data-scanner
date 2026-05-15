-- V2: pgvector corpus for metadata-similarity heuristic (impersonation detection)
-- Stores embeddings of known-legitimate token metadata.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE corpus_entries (
    id                  BIGSERIAL PRIMARY KEY,
    symbol              VARCHAR(32)  NOT NULL,
    name                VARCHAR(128) NOT NULL,
    description         TEXT,
    canonical_address   VARCHAR(42)  NOT NULL,
    canonical_deployer  VARCHAR(42)  NOT NULL,
    chain               VARCHAR(32)  NOT NULL DEFAULT 'ethereum',
    category            VARCHAR(32),                              -- stablecoin, sovereign-debt, etc.
    embedding           VECTOR(1536),                             -- bge-m3 = 1024; text-embedding-3-small = 1536; adjust per provider
    source              VARCHAR(32)  NOT NULL,                    -- 'seed' | 'admin_review'
    added_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    added_by            VARCHAR(64)
);

CREATE INDEX idx_corpus_symbol ON corpus_entries(symbol);
CREATE INDEX idx_corpus_address ON corpus_entries(canonical_address);
CREATE INDEX idx_corpus_category ON corpus_entries(category);

-- HNSW index for fast cosine-similarity search; tuning params chosen for ~50-500 corpus entries
CREATE INDEX idx_corpus_embedding_cosine ON corpus_entries
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
