# ADR-004: pgvector corpus for impersonation detection

**Status:** Accepted

## Context

The metadata-similarity heuristic detects token-name impersonation: someone deploys "USDC2" or "uSDC" from a wallet that has nothing to do with Circle. The detection logic is conceptually simple — embed the new token's `symbol + name`, compare against a corpus of known-legitimate tokens, flag if the closest match is very similar AND the deployer EOA does not match the canonical deployer of that match.

The corpus storage choices:

1. **Postgres + pgvector** (chosen) — embeddings in a `vector(1536)` column, HNSW index, cosine search via `<=>` operator
2. Standalone vector DB (Pinecone, Qdrant, Weaviate)
3. Faiss in memory, persisted to a flat file
4. Hand-curated CSV with substring matching (no embeddings)

## Decision

**Postgres + pgvector** in the same database the rest of the scanner uses.

Schema (V2 migration):

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE corpus_entries (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    canonical_address VARCHAR(42) NOT NULL,
    canonical_deployer VARCHAR(42) NOT NULL,
    chain VARCHAR(32) NOT NULL DEFAULT 'ethereum',
    category VARCHAR(32),
    embedding VECTOR(1536),                 -- text-embedding-3-small
    source VARCHAR(32) NOT NULL,
    added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    added_by VARCHAR(64)
);

CREATE INDEX idx_corpus_embedding_cosine ON corpus_entries
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
```

V4 migration adds a UNIQUE constraint on `canonical_address` so the upsert path in `CorpusIngestionService` is idempotent.

Embedding model: **OpenAI `text-embedding-3-small`** — 1536 dimensions, $0.02 per 1M tokens (~$0.0002 per 100-token corpus refresh). Chosen over `text-embedding-3-large` (3072 dims, more expensive) because the corpus is small and the impersonation patterns are short-text matches; the 1536-dim model captures them sufficiently.

## Consequences

**Positive:**

- One database, one connection pool, one backup story. The corpus participates in the same transactional context as the rest of the scanner.
- pgvector's HNSW index gives sub-millisecond cosine search for the corpus sizes we expect (top-100 to top-1000 tokens). No need for a separate vector DB tier.
- The `canonical_deployer` column is the load-bearing piece — without it, the heuristic could only say "the new token is similar to a known token" (false positives everywhere). With it, the signal becomes "similar AND deployed by someone else" (true impersonation pattern).

**Negative:**

- Hibernate cannot map `vector(1536)` to a Kotlin type without a custom `UserType`. Worked around by routing the embedding column through `JdbcTemplate` in `CorpusEntryDao` (the metadata columns still go through JPA).
- HNSW index is approximate. With only ~100-1000 entries this is fine; recall is essentially 100%. At 100k+ entries we would need to revisit `m` and `ef_construction`.
- OpenAI is now a required dependency for both corpus refresh AND every per-pool detection (the heuristic embeds the new token's metadata before searching). If OpenAI is degraded, the heuristic returns `confidence = 0` and the composite scorer ignores it — but the failure mode is "this heuristic stops working" rather than "graceful degradation to a different signal".

## Alternatives considered

- **Pinecone / Qdrant / Weaviate**: rejected — adds a managed-service dependency, second connection string, second auth surface. pgvector handles this scale natively.
- **Faiss in memory**: rejected — no persistence story, no concurrent-write story, no SQL composability with the rest of the corpus metadata.
- **Hand-curated CSV with substring matching**: rejected and explicitly called out as a non-goal in the README. A CSV that says "USDC's deployer is 0xabc" when 0xabc is wrong silently breaks the heuristic; the corpus must be sourced programmatically with deployer resolution from Etherscan, not typed by a human.
