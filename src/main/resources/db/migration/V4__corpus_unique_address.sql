-- V4: enforce one corpus row per canonical_address so the embedding upsert
-- in CorpusEntryDao can use ON CONFLICT idempotently. Re-running the corpus
-- ingestion job overwrites the embedding instead of appending duplicates.

ALTER TABLE corpus_entries
    ADD CONSTRAINT uq_corpus_canonical_address UNIQUE (canonical_address);
