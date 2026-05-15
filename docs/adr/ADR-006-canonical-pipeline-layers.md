# ADR-006: Canonical six-layer blockchain analytics pipeline

**Status:** Accepted

## Context

Reviewers of a blockchain-analytics codebase quickly form a verdict on whether
the architecture looks like a pipeline they recognise. If the layer boundaries
are unfamiliar or invented, the conversation gets stuck on shape; if they are
canonical, the conversation moves to what actually differentiates this repo —
the heuristics and the operator surface.

The reference layer model used here is the one Allium publishes in
*"Blockchain Analytics Stack Explained: Architecture, Layers and Tools"*
([allium.so/blog](https://www.allium.so/blog/blockchain-analytics-stack-explained-architecture-layers-and-tools)).
It is a six-layer pipeline:

```
Data Ingestion (Node Infra)  ->  Normalization & Decoding  ->  Indexing
        ->  Storage & Warehouse  ->  Query & Analytics  ->  Application & Insight
```

Each layer has a single, narrow responsibility; consecutive layers are coupled
only through a typed contract. This ADR records the explicit mapping of
packages, classes, and third-party libraries onto those six layers, plus the
abstractions extracted to keep each layer open for extension.

## Decision

The packages line up to Allium's layers as follows. **No package renames** —
the existing `chain/`, `ingestion/`, `persistence/`, `analysis/`, `api/`,
`admin/`, `corpus/` packages each map to one (or in two cases, a contiguous
pair) of Allium's layers. Cross-cutting concerns (`config/`, `observability/`)
sit beside the pipeline, not in it.

| Allium layer | Role | Our packages | Key types | Library |
|---|---|---|---|---|
| 1. Data Ingestion (Node Infra) | Subscribe / pull blocks + logs from RPC | `chain/` (sources) + `ingestion/` (orchestration) | `BlockSource` (sealed contract), `LiveBlockSource` (WSS), `BackfillBlockSource` (getLogs), `IngestionPipeline` | web3j 4.12 (HTTP + WebSocket transports) |
| 2. Normalization & Decoding | ABI-decode raw logs into typed events | `chain/` (decoders) | `EventDecoder<T>` (extracted in this ADR), `PoolCreatedDecoder : EventDecoder<TokenContext>`, `MintLogReader`, `TransferLogReader`, `NpmPositionTracer` | web3j ABI codec (`Event`, `EventEncoder`, `FunctionReturnDecoder`) |
| 3. Indexing | Organise normalised events into queryable tables with the right keys + indexes | `persistence/` (entities + repositories) | `PoolDetection`, `ProcessedBlock` (compound PK `(block_number, block_hash)`), `IngestionRun`, `DeployerReputation`, `CorpusEntryDao` | Spring Data JPA + Hibernate; Flyway-versioned schema (`V4__indexes.sql` adds the analytical-query indexes) |
| 4. Storage & Warehouse | The actual persistent store + enrichment writes | Postgres + pgvector container; `corpus/` (embedding enrichment) | `CorpusIngestionService`, `corpus_entries.embedding vector(1536)` | Postgres 16 + pgvector; Spring AI `EmbeddingModel` (OpenAI text-embedding-3-small) |
| 5. Query & Analytics | SQL / API surface that turns indexed data into per-detection insights | `analysis/` (heuristic queries) + `api/` (read controllers) | `RiskHeuristic` interface, `CompositeScorer` (parallel `async`/`awaitAll`), `ChartsApiController`, `StatsService`, `EvidenceSummaryService` | JPA criteria + JdbcTemplate for vector ops; Spring AI `ChatModel` (Anthropic) for natural-language evidence summaries |
| 6. Application & Insight | Operator-facing dashboards, monitoring, decisions | `api/` (UI controllers), `admin/`, `templates/`, `static/` | `DashboardController`, `HeuristicTabsController`, `PoolStreamController` (SSE), `AdminController`, `ReviewService`, `LogsController` | Spring Web MVC + Thymeleaf + htmx + Reactor `Sinks.Many` for SSE; Chart.js for in-page charts |

**Cross-cutting (not in any layer):**
- `observability/`: Micrometer + Prometheus + Logback JSON + an in-memory ring buffer for the live event-log tab.
- `config/`: Spring Security (basic auth on everything bar the Fly liveness probe + metrics scraper), Caffeine cache beans, Resilience4j retry/circuit-breaker, web3j HTTP / WSS beans.

## Consequences

### Positive

- **Extensibility per layer is now stated, not implied.** Each layer has at
  least one abstraction that lets the next contributor add a peer
  implementation without touching the layers above or below:
  - L1: `BlockSource` (sealed contract) — already pluggable (live + backfill share
    the entire `IngestionPipeline`).
  - L2: `EventDecoder<T>` (extracted in this ADR) — `PoolCreatedDecoder` is one
    impl; a Sushi V3 decoder, Uniswap V4 decoder, or Curve pool decoder is just
    a new class. The `IngestionPipeline` does not know which factory it is
    listening to.
  - L3: Spring Data repository interfaces — adding a new `XxxRepository` is one
    file; no JdbcTemplate boilerplate.
  - L4: Spring AI's `EmbeddingModel` + `VectorStore` abstractions — swapping
    OpenAI text-embedding-3-small for Workers AI bge-m3 is a single bean
    redefinition.
  - L5: `RiskHeuristic` interface — adding the 9th heuristic is one file +
    `@Component` annotation; the parallel scorer picks it up automatically.
  - L6: htmx + SSE — adding a tab is one Thymeleaf template + one route.
- **Reviewers stop arguing about the shape and start asking about the heuristics.**
  When the package layout maps 1:1 to a published industry stack, the
  load-bearing question becomes "how good is your DeployerHistory signal", not
  "why is enrichment in the `analysis` package".
- **Each layer is mostly third-party.** L1 is web3j, L3 is Spring Data, L4 is
  Postgres + Spring AI vector store, L6 is Spring MVC + htmx. The code we own
  is the per-detection orchestration in L2 (decoders) and L5 (heuristics) —
  precisely the parts that should be ours, because that is where the domain
  judgment lives.

### Negative

- The `chain/` package straddles L1 (sources) and L2 (decoders). The split is
  obvious from class names (`*BlockSource` vs `*Decoder` / `*Reader`) but a
  future contributor might want to break `chain/` into `chain/sources/` +
  `chain/decoding/` subpackages. Deferred — package surgery for cosmetic
  reasons is exactly the kind of churn this ADR is trying to head off.
- The `analysis/` package contains both the heuristic *contract* (L5: pure
  query) and the heuristic *queries that enrich raw on-chain reads* (some
  heuristics blur into L2/L4 because they call `eth_getLogs` themselves). This
  is intentional — splitting "fetch + decode" out of each heuristic into a
  separate enrichment-step abstraction would be premature now (we have 3 real
  heuristics; the right shared abstraction emerges around heuristic 5+).

### Rejected alternatives

- **Rename packages to `nodes/`, `decoding/`, `indexing/`, `warehouse/`,
  `query/`, `application/`.** Rejected: high import churn for purely cosmetic
  alignment. The mapping table above is the durable artefact; package names
  follow standard JVM ergonomics.
- **Introduce an explicit `EnrichmentStep` interface for `EtherscanClient` /
  `PriceOracle` / `MintLogReader`.** Rejected as premature. Each enricher is
  used by exactly one heuristic today; the abstraction would be speculative.
  Revisit when two heuristics share an enricher.
- **Use Spring Batch for backfill mode.** Rejected: `BackfillBlockSource`
  emitting a coroutine `Flow<TokenContext>` into the same `IngestionPipeline`
  as live mode is the cleaner story (and the central architectural claim of
  ADR-002). Spring Batch would re-introduce a separate execution path that
  ADR-002 explicitly rejected.

## References

- ADR-001 — stack choice (Kotlin + Spring Boot + web3j)
- ADR-002 — two-table ingestion ledger; central claim that live and backfill
  share the pipeline
- ADR-005 — three-implemented + five-stubbed heuristic balance
- Allium, *"Blockchain Analytics Stack Explained: Architecture, Layers and Tools"*
