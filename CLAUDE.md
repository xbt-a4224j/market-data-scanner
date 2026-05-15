# CLAUDE.md - Market Data Scanner

Read this before doing anything in this repo. This is the source of truth for the architecture, scope, and decisions.

---

## What this repo is

A real-time risk-surveillance pipeline for new token launches on Uniswap V3 (Ethereum mainnet). Built in Kotlin + Spring Boot + web3j with Postgres + pgvector for storage and Spring AI for embedding-based heuristics.

The goal is production-shaped blockchain data engineering: real ingestion-pipeline discipline, observability, and architectural maturity, not a vibe demo of heuristics.

---

## What the system does

For every new Uniswap V3 pool created on Ethereum mainnet, the system:

1. Detects the pool via web3j WebSocket subscription to the factory contract.
2. Identifies the "novel" token (non-WETH, non-stablecoin side of the pair).
3. Runs a battery of risk heuristics against that token in parallel.
4. Produces a composite risk score (0-100) with per-heuristic evidence.
5. Persists to Postgres for the dashboard.
6. Streams updates to a live dashboard via SSE.

A live web dashboard shows the streaming feed, with one tab per heuristic and an admin panel for human review. Reviewed-legitimate tokens feed back into the metadata-similarity corpus, closing the human-in-the-loop loop.

The pipeline supports two modes:

- **Live (default)** - subscribes to live blocks via web3j WebSocket.
- **Backfill** - replays a historical block range (or "last X days") through the same pipeline.

---

## The heuristic stack

Three real implementations, five scaffolded stubs.

### Implemented

**1. Supply Concentration** (`SupplyConcentrationHeuristic`)
Walks `Transfer` events from the new token contract. Computes top-K holder concentration. Output: top-1 holder %, top-3 holder %, Gini coefficient.
Signal: token with 95 %+ supply in 1-3 wallets after deployment is a textbook rug-pull setup.
Real-time: yes, one `eth_getLogs` call after pool creation.

**2. LP Lock + Initial Liquidity** (`LpLockHeuristic`)
Tracks the LP token's first `Transfer` event after the pool's first `Mint`. Identifies destination as: deployer wallet (high rug risk), burn address (locked, low risk), known lock contract (Unicrypt, Team Finance, Pinklock, Mudra Locker - addresses hardcoded), or unknown (yellow).
Also captures initial liquidity in USD via the WETH/USDC side of the pool.
Signal: deployer-held LP + low initial liquidity = rug pattern.

**3. Deployer History Pattern** (`DeployerHistoryHeuristic`)
The "beefy" heuristic. For the deployer EOA, enumerates all prior ERC-20 contract deployments. For each, evaluates outcome: median lifetime, fraction abandoned (<$100 final liquidity), Etherscan label (Fake_Phishing, etc.).
Signal: a serial scam-factory deployer fingerprint.

**Validation requirement:** the test fixture must hit a known Etherscan-labeled phishing factory (e.g. a `Fake_Phishing*` address with 50+ prior deployments). The heuristic should score that deployer above 0.85 with high confidence. This validation test is non-negotiable - it is what makes the heuristic credible as a real signal vs a toy.

### Stubbed (full interface scoped, implementation deferred)

**4. Metadata Similarity** (`MetadataSimilarityHeuristic`)
Spring AI's `EmbeddingClient` against a pgvector cosine similarity index over a curated legitimate-token corpus. Catches impersonation tokens (e.g. "USDC2", "uSDC").
*Stubbed because:* (a) conceptually simple - it is name-matching with embedding semantics - so the implementation budget was better invested in more sophisticated heuristics + pipeline maturity; (b) the corpus itself needs proper ingestion (see Issue #19 in ISSUES.md), not a hand-curated CSV that creates fake confidence about deployer addresses.
The interface is fully scoped. The pgvector schema (`corpus_entries`) is migrated. The corpus stays empty until Issue #19 ships; at that point the heuristic implementation is a small wrapper that becomes useful.

**5. Funding-Flow Attribution** (`FundingFlowHeuristic`)
Trace deployer EOA's funding source back N hops. Detect Tornado Cash / mixer touches, sanctioned-entity exposure, known scam-wallet patterns.

**6. Source-Code Pattern Analysis** (`SourceCodePatternHeuristic`)
When the contract is verified on Etherscan, AST-parse the Solidity source for known scam patterns: hidden fees activated after N blocks, whitelist transfer modifiers, owner-only mint without timelock, suspicious "rescue" functions.

**7. First-N-Buyer Wallet Graph** (`FirstNBuyersHeuristic`)
For the first 20 buyers of a new token, analyze funding sources. Sybil pump = many buyer wallets funded from one source within a short window. Output: cluster signature.

**8. Social-Media Correlation** (`SocialMediaCorrelationHeuristic`)
Cross-reference token name + contract address against Telegram / X mentions in the prior hour. Out-of-scope for this repo's chain-side focus but scaffolded for completeness.

Each stub implements the `RiskHeuristic` interface returning a `HeuristicResult` with `stubbed = true`. The dashboard renders a clear "STUBBED" badge for these.

---

## Architecture & pipeline maturity

This is the part that distinguishes this from a vibe demo. Each item below is non-negotiable for production-shaped pipeline thinking.

### Ingestion ledger (the load-bearing piece)

Two-table state:

**`ingestion_runs`** - high-level execution records. Each live subscription is one run; each backfill is another run. Status (running / completed / failed), from/to block, last processed block, pools detected count.

**`processed_blocks`** - per-block ledger with PK `(block_number, block_hash)`. Survives across runs. Three purposes:

- **Idempotency:** re-processing the same `(number, hash)` is a no-op (PK conflict on insert).
- **Reorg detection:** if you see block N with hash H' when the ledger already has (N, H), mark old (N, H) as reorged, process new (N, H').
- **Resumability:** crash mid-backfill -> restart skips already-processed blocks.

### Watermark semantics

Since blocks are monotonically increasing, track a single contiguous-processed range scalar in an `ingestion_state` row (latest_processed_block, earliest_processed_block). Live extends forward; backfill extends backward.

**Backfill API:**

```bash
# By date range
POST /admin/backfill { "fromDate": "2025-05-08T00:00:00Z", "toDate": "2025-05-09T00:00:00Z" }
# By block range
POST /admin/backfill { "fromBlock": 22500000, "toBlock": 22510000 }
# Look-back shortcut (most common pattern)
POST /admin/backfill { "lookbackDays": 7 }
```

Date -> block resolution via Etherscan API (`getBlockNoByTime`) or a local block-timestamp index.

### Reorg-aware ingestion

Block tracking by hash, not just number. On reorg detection: old `pool_detections` from the orphaned block are marked `status='reorged'` (not deleted - preserved for audit). Live processing continues with the canonical chain.

### Heuristic versioning + audit trail

Every `HeuristicResult` carries `heuristicVersion` (semver). Every persisted `PoolDetection` records the version of each heuristic that produced its score, plus an `inputsHash` over the canonical inputs to that heuristic. When the heuristic logic changes, old scores remain reproducible.

### Rate limit + caching

- **Caffeine in-memory cache** for `getDeployerHistory(eoa)` - same EOA queried within 5min returns cached result.
- **Resilience4j retry-with-backoff** for transient RPC failures.
- **Circuit breaker** if Etherscan API errors persistently - heuristics that depend on it return partial-confidence results instead of crashing.

### Observability

- **Spring Actuator + Micrometer + Prometheus** exposed at `/actuator/prometheus`.
- **Counters:** `pools_detected_total`, `heuristic_fired_total{heuristic=...}`, `errors_total{type=...}`.
- **Histograms:** `heuristic_latency_seconds{heuristic=...}`.
- **Gauges:** `latest_processed_block`, `seconds_since_last_block`.
- **Structured logging** via Logback JSON output - keys: `tx_hash`, `block_number`, `pool_address`, `score`, `heuristic`.

### Schema migrations

**Flyway** for versioned SQL migrations. Files in `src/main/resources/db/migration/`. Auto-applied on app startup. No `hibernate.ddl-auto=update` (that is a footgun in production).

### Authentication

Spring Security HTTP Basic auth. Single admin user, credentials via env vars (`ADMIN_USERNAME`, `ADMIN_PASSWORD`). Protects `/admin/**` endpoints. The live SSE feed at `/stream` can be public or protected - set via config.

### CI

GitHub Actions runs `./gradlew test` on every push to a PR or to `main`. Required status check before merge.

### Architecture Decision Records

`docs/adr/` folder with markdown files for major decisions:

- ADR-001: Kotlin + Spring Boot + web3j over alternative stacks.
- ADR-002: Two-table ingestion ledger with watermark.
- ADR-003: Composite scoring weights and calibration approach.
- ADR-004: pgvector for impersonation detection corpus.
- ADR-005: Three-implemented + five-stubbed heuristic balance.

---

## Repo layout

```
market-data-scanner/
├── build.gradle.kts                    # Spring Boot + web3j + Spring AI + pgvector
├── gradle/wrapper/                     # gradle wrapper
├── docker-compose.yml                  # local Postgres + pgvector
├── fly.toml                            # Fly.io deployment config
├── .github/workflows/test.yml          # CI: gradle test on push
├── README.md
├── CLAUDE.md                           # this file
├── ISSUES.md                           # GitHub issues breakdown
│
├── docs/adr/
│   ├── ADR-001-stack-choice.md
│   ├── ADR-002-ingestion-ledger.md
│   ├── ADR-003-scoring-weights.md
│   ├── ADR-004-pgvector-corpus.md
│   └── ADR-005-heuristic-scoping.md
│
├── src/main/kotlin/io/github/xbta4224j/scanner/
│   ├── ScannerApplication.kt           # Spring Boot entrypoint
│   │
│   ├── chain/
│   │   ├── BlockSource.kt              # sealed interface
│   │   ├── LiveBlockSource.kt          # web3j WebSocket subscription
│   │   ├── BackfillBlockSource.kt      # historical block iterator
│   │   ├── PoolCreatedDecoder.kt       # decode V3 factory events
│   │   ├── TokenContext.kt             # canonical token info
│   │   └── ReorgDetector.kt            # block-hash mismatch handling
│   │
│   ├── ingestion/
│   │   ├── IngestionPipeline.kt        # the shared pipeline (consumes any BlockSource)
│   │   ├── IngestionMode.kt            # LIVE | BACKFILL enum
│   │   └── WatermarkService.kt         # current contiguous range tracking
│   │
│   ├── analysis/
│   │   ├── RiskHeuristic.kt            # interface
│   │   ├── HeuristicResult.kt          # data class w/ score, confidence, evidence, version, inputsHash
│   │   ├── CompositeScorer.kt          # weighted sum, parallel via coroutines
│   │   └── heuristics/
│   │       ├── SupplyConcentrationHeuristic.kt     [IMPL]
│   │       ├── LpLockHeuristic.kt                   [IMPL]
│   │       ├── DeployerHistoryHeuristic.kt          [IMPL - with phishing-factory validation test]
│   │       ├── MetadataSimilarityHeuristic.kt       [STUB]
│   │       ├── FundingFlowHeuristic.kt              [STUB]
│   │       ├── SourceCodePatternHeuristic.kt        [STUB]
│   │       ├── FirstNBuyersHeuristic.kt             [STUB]
│   │       └── SocialMediaCorrelationHeuristic.kt   [STUB]
│   │
│   ├── persistence/
│   │   ├── PoolDetection.kt            # JPA entity for detected pools
│   │   ├── PoolDetectionRepository.kt
│   │   ├── IngestionRun.kt             # ingestion_runs entity
│   │   ├── IngestionRunRepository.kt
│   │   ├── ProcessedBlock.kt           # processed_blocks entity (compound PK)
│   │   ├── ProcessedBlockRepository.kt
│   │   ├── CorpusEntry.kt              # legit-token corpus entity (with embedding)
│   │   └── CorpusLoader.kt             # @PostConstruct seeds CSV -> pgvector
│   │
│   ├── api/
│   │   ├── DashboardController.kt      # serves htmx templates
│   │   ├── PoolStreamController.kt     # SSE for live updates
│   │   ├── PoolDetailController.kt     # per-pool JSON
│   │   └── MetricsConfig.kt            # exposes /actuator/prometheus
│   │
│   ├── admin/
│   │   ├── AdminController.kt          # review queue + backfill triggers
│   │   ├── ReviewService.kt            # human-in-the-loop labeling
│   │   ├── BackfillController.kt       # POST /admin/backfill
│   │   └── ReviewDecision.kt           # label + corpus auto-add on legit
│   │
│   ├── config/
│   │   ├── SpringAiConfig.kt           # ChatModel beans
│   │   ├── Web3Config.kt               # web3j HttpService + WebSocketService
│   │   ├── SecurityConfig.kt           # Spring Security basic auth
│   │   └── CacheConfig.kt              # Caffeine cache beans
│   │
│   └── observability/
│       ├── MetricsRegistry.kt          # custom metrics for heuristic firings
│       └── StructuredLogger.kt         # JSON logging helpers
│
├── src/main/resources/
│   ├── application.yml
│   ├── data/
│   │   └── legit-tokens.csv            # corpus seed anchor (empty until Issue #19)
│   ├── db/migration/
│   │   ├── V1__init.sql                # ingestion_runs, processed_blocks, pool_detections
│   │   ├── V2__corpus.sql              # pgvector table for embeddings
│   │   └── V3__review_decisions.sql    # admin labeling audit trail
│   ├── templates/
│   │   ├── layout.html                 # tab bar shared chrome
│   │   ├── overview.html               # default tab: composite feed
│   │   ├── heuristics/
│   │   │   ├── supply-concentration.html
│   │   │   ├── lp-lock.html
│   │   │   ├── deployer-history.html
│   │   │   ├── metadata-similarity.html   (stubbed view)
│   │   │   ├── funding-flow.html          (stubbed view)
│   │   │   ├── source-code.html           (stubbed view)
│   │   │   ├── first-n-buyers.html        (stubbed view)
│   │   │   └── social-media.html          (stubbed view)
│   │   ├── admin/
│   │   │   ├── queue.html              # review queue
│   │   │   ├── backfill.html           # trigger UI
│   │   │   └── runs.html               # ingestion run history
│   │   └── fragments/
│   │       ├── feed-row.html
│   │       ├── score-badge.html
│   │       └── signal-evidence.html
│   └── static/
│       ├── css/dashboard.css
│       └── js/charts.js                # Chart.js helpers, no build step
│
└── src/test/
    ├── kotlin/io/github/xbta4224j/scanner/
    │   ├── analysis/heuristics/
    │   │   ├── SupplyConcentrationHeuristicTest.kt
    │   │   ├── LpLockHeuristicTest.kt
    │   │   └── DeployerHistoryHeuristicTest.kt    # MUST include phishing-factory validation
    │   ├── chain/PoolCreatedDecoderTest.kt
    │   ├── ingestion/IngestionPipelineTest.kt
    │   └── persistence/ProcessedBlockRepositoryTest.kt
    └── resources/
        ├── eval/
        │   ├── known-scam-tokens.csv         # known scam tokens for backtest (Issue #20)
        │   ├── known-legit-tokens.csv        # known legit launches (Issue #20)
        │   └── metadata-similarity-cases.csv # impersonation eval set
        └── fixtures/
            ├── supply-concentration-fixtures.csv
            └── lp-lock-fixtures.csv
```

---

## Build & run

### Local development

```bash
# Spin up Postgres + pgvector (host port 5433)
docker compose up -d

# Run migrations + boot service
./gradlew bootRun

# Or build a fat jar
./gradlew bootJar
java -jar build/libs/scanner-0.1.0.jar
```

Environment variables (defined in `application.yml` defaults; override via env or `.env`):

```
INFURA_API_KEY=<project-id>                          # or set ETHEREUM_RPC_URL / ETHEREUM_WS_URL directly
ETHERSCAN_API_KEY=<key>
ANTHROPIC_API_KEY=<key>                              # used by admin/corpus + per-pool evidence summaries
ADMIN_USERNAME=admin
ADMIN_PASSWORD=<set-something>
DATABASE_URL=jdbc:postgresql://localhost:5433/scanner
DATABASE_USERNAME=scanner
DATABASE_PASSWORD=scanner
```

### Tests

```bash
./gradlew test
```

The deployer-history heuristic test MUST hit a known phishing-factory address and assert high confidence. If this test ever turns red, the heuristic logic regressed - fix before merging.

### Deploy to Fly.io

```bash
fly launch                # one-time setup
fly secrets set ANTHROPIC_API_KEY=... ETHEREUM_RPC_URL=... ETHERSCAN_API_KEY=... ADMIN_PASSWORD=...
fly deploy
```

Provision Postgres separately:

```bash
fly postgres create --name scanner-pg
fly postgres attach scanner-pg
# pgvector extension: connect via fly proxy and CREATE EXTENSION vector;
```

---

## Stack summary

- **Language:** Kotlin 1.9+ on JVM 17+
- **Framework:** Spring Boot 3.2+
- **Web3:** web3j 4.12+ (auto-generated contract bindings)
- **DB:** PostgreSQL 16 with pgvector extension
- **ORM:** Spring Data JPA + Hibernate
- **Migrations:** Flyway
- **LLM provider:** Spring AI (Anthropic)
- **Vector store:** Spring AI's pgvector store (wired in Issue #19)
- **Cache:** Caffeine
- **Resilience:** Resilience4j (retry, circuit breaker)
- **Security:** Spring Security (HTTP basic auth)
- **Observability:** Spring Actuator + Micrometer + Prometheus + Logback JSON
- **Web UI:** Spring Web MVC + Thymeleaf + htmx (no JS build step)
- **Tests:** JUnit 5 + Mockk + Testcontainers (Postgres)
- **CI:** GitHub Actions
- **Deploy:** Fly.io (JVM image + managed Postgres)

---

## What this is NOT

- Not a multi-chain scanner. Ethereum mainnet only. Cross-chain is in the "next iteration" notes, not the code.
- Not a multi-DEX scanner. Uniswap V3 only. SushiSwap, PancakeSwap, etc. are stubs in the source-discovery layer.
- No emojis in code, comments, commits, dashboard text, or PR descriptions. The no-emoji discipline is a tech-lead habit worth maintaining.

---

## Where to start

1. Read this entire CLAUDE.md.
2. Open `ISSUES.md` for the issue breakdown.
3. Open Issue #1 and start scaffolding. Don't deviate from the architecture described above without flagging the deviation explicitly in a commit message.
4. After each issue: commit, push, watch CI pass. Move to next.
