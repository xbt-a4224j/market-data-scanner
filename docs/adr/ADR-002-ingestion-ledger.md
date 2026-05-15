# ADR-002: Two-table ingestion ledger with watermark

**Status:** Accepted

## Context

The scanner runs two ingestion modes against the same downstream pipeline:

1. **Live** — WebSocket subscription to the Uniswap V3 factory.
2. **Backfill** — historical block-range iterator triggered from `/admin/backfill`.

Both modes must:

- Be **idempotent**: re-processing the same `(blockNumber, blockHash)` is a no-op (no duplicate `pool_detections`).
- Be **reorg-aware**: when a competing canonical hash appears at a height we already processed, the old detections must be marked `reorged` (not deleted — preserved for audit).
- Be **resumable**: a backfill that crashes mid-range must, on relaunch, skip already-processed blocks and pick up where it left off.
- Track a **watermark** scalar so the dashboard can answer "how far back / forward have we processed".

## Decision

Three tables in the V1 Flyway migration:

### `ingestion_runs`

One row per ingestion execution. `mode IN ('live', 'backfill')`, `status IN ('running','completed','failed','reorged')`, plus `from_block`, `to_block`, `last_processed_block`, `pools_detected`. Records the lifecycle of each run.

### `processed_blocks`

Per-block ledger keyed on the **compound primary key `(block_number, block_hash)`**. Three load-bearing roles:

- **Idempotency**: `INSERT` with the same PK conflicts → no-op. The `IngestionPipeline` swallows the conflict (`runCatching {}.onFailure { log.debug }`).
- **Reorg detection**: on every emit, query `findCanonicalAtHeightWithDifferentHash(number, newHash)` — any rows returned are competing canonicals. The pipeline issues a JPQL UPDATE marking them `status='reorged'` and flips associated `pool_detections` rows to `reorged` as well (preserved for audit, hidden from default dashboard queries).
- **Resumability**: `BackfillBlockSource.subscribe()` calls `existsByBlockNumberAndBlockHash` per block before emitting. Crashed backfills, on restart, skip everything already done.

### `ingestion_state`

Singleton row (id = 1, CHECK constraint enforces) with `earliest_processed_block` and `latest_processed_block`. `WatermarkService` exposes `extendForward(n, hash)` (live mode) and `extendBackward(n)` (backfill) with monotonicity guards: forward only advances if `n > latest`, backward only retreats if `n < earliest`. Eventually a single contiguous-processed range emerges from a live subscription + a few backfill runs.

## Consequences

**Positive:**

- Same `IngestionPipeline.run(source: BlockSource)` works for both live and backfill — the abstraction is the seam. The pipeline does not know it is running historically.
- The compound PK is a strong invariant: it is impossible to double-record the same canonical block, even under concurrent writers.
- Reorged data is preserved, not deleted — `pool_detections.status = 'reorged'` rows are still in the database for audit; they are just filtered out of the default `findTop50ByOrderByDetectedAtDesc` query the dashboard uses.

**Negative:**

- Reorg handling requires JPQL `@Modifying` UPDATE which Spring requires to run inside a transaction. `IngestionPipeline.processOne` is `suspend` and so cannot use `@Transactional` (Spring's proxy doesn't compose with Kotlin coroutines — the interceptor wraps the synchronous bytecode, not the coroutine body). Worked around with an explicit `TransactionTemplate` injected into the pipeline; reorg-handling and persistence each run inside `tx.executeWithoutResult { ... }`.
- The watermark singleton has high write contention if both live and backfill run concurrently. For the current load (one live writer, occasional manual backfills) this is fine; under heavier multi-backfill load the singleton would need a `SELECT ... FOR UPDATE` row lock or a per-mode partition.

## Alternatives considered

- **Single-table ledger keyed only on block_number.** Rejected — would lose reorg history (same height with different hash would either conflict-fail or overwrite the canonical record with no audit trail).
- **Append-only event log with a separate materialized view for "latest canonical state".** Rejected as overkill at current scale; reconsider if the scanner needs to reproduce historical state at arbitrary timestamps.
- **External orchestrator (e.g. Temporal / Airflow) for backfills.** Rejected for the demo — adds a service dependency and a deployment story we do not need.
