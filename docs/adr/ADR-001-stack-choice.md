# ADR-001: Stack choice — Kotlin + Spring Boot + web3j on JVM 17

**Status:** Accepted

## Context

The scanner needs:

- A long-running WebSocket subscription to an Ethereum RPC node (factory `PoolCreated` events).
- Synchronous HTTP RPC calls under that subscription (Transfer-event walks per detection, Etherscan calls).
- Concurrent execution of N independent heuristics per pool.
- A polished operator dashboard with live updates (SSE).
- Pgvector cosine search for the impersonation heuristic.
- Production-shaped pipeline discipline: Flyway migrations, idempotent reorg-aware ingestion, structured observability.

The candidate stacks were:

1. **Kotlin + Spring Boot + web3j** (chosen)
2. Rust + ethers-rs + axum + sqlx
3. TypeScript + ethers.js + Fastify + Prisma
4. Python + web3.py + FastAPI

## Decision

**Kotlin on Spring Boot 3.2 + web3j 4.12 + JVM 17.** Coroutines for the in-process concurrency story (parallel heuristic execution via `async`/`awaitAll`), Spring Data JPA + Flyway + Spring Security + Actuator for the operational surface, web3j for typed Ethereum bindings.

## Consequences

**Positive:**

- Web3j gives ABI-typed event decoders out of the box (no hand-rolling topic decoders).
- Spring AI 1.0 has first-class support for both Anthropic chat (per-pool evidence summaries) and OpenAI embeddings (corpus pipeline) as separate auto-configured beans.
- Spring Boot Actuator + Micrometer + Prometheus is one dependency; rolling our own metrics surface in Rust/TS/Python would have been weeks of plumbing.
- Spring Data JPA + Flyway is a mature combo for the schema-versioned ledger story (see ADR-002).
- Coroutines compose cleanly with the suspend-based `RiskHeuristic.evaluate()` interface; parallel execution + per-heuristic failure isolation comes from `runCatching` inside `async`.

**Negative:**

- ~5-6 second JVM startup time. Acceptable for a long-running service; would be painful for scale-to-zero serverless.
- Kotlin `suspend fun` does NOT compose with Spring's `@Transactional` annotation (the proxy interceptor wraps the synchronous bytecode, not the coroutine body). Worked around in `IngestionPipeline.processOne` via explicit `TransactionTemplate`. See ADR-002 for the full pattern.
- Web3j's WebSocket transport opens a network connection at bean construction time. Made `@Lazy` in `Web3Config` so context-load tests stay hermetic.

**Rejected alternatives:**

- **Rust** would have given lower memory footprint and faster startup but the developer-velocity hit on a multi-week solo project was the deciding factor — Spring Data JPA repositories with custom queries are 5-line interfaces; the equivalent in sqlx is hand-written SQL. The pipeline-maturity goals (idempotency, reorg, audit trail) cost more dev hours in Rust than the runtime savings buy back at this scale.
- **TypeScript** would have had the most mature ethers.js bindings but no equivalent of Spring AI's provider-agnostic chat / embedding abstraction. Wiring Anthropic + OpenAI separately in TS is more code, not less.
- **Python** has the easiest scientific-tooling story for the heuristic side but is weakest on the long-running JVM-style server pattern. Async ergonomics with web3.py are still rough.
