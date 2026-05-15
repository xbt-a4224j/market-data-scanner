# CLAUDE.md — Market Data Scanner

Read this before doing anything in this repo. This is the source of truth for the architecture, scope, and decisions.

---

## What this repo is

A real-time risk-surveillance pipeline for new token launches on Uniswap V3 (Ethereum mainnet). Built in Kotlin + Spring Boot + web3j with Postgres+pgvector for storage and Spring AI for embedding-based heuristics.

**Built as a portfolio artifact for the operator's interview with (scrubbed), Lead Engineer (Commercial side) at (scrubbed).** The goal is to demonstrate tech-lead-grade blockchain data engineering — real ingestion-pipeline discipline, observability, and architectural maturity — not a vibe demo of heuristics. (scrubbed) runs the operator's commercial-side pipelines; this project is designed to match the shape of work he does.

the operator's stack is Spring + JVM for backbone services (per (scrubbed)'s (scrubbed) Medium post) and Cloudflare Workers for edge. This repo deliberately lives on the JVM side to align with the production backbone, not the edge experimental layer.

---

## What the system does

For every new Uniswap V3 pool created on Ethereum mainnet, the system:

1. Detects the pool via web3j WebSocket subscription to the factory contract
2. Identifies the "novel" token (non-WETH, non-stablecoin side of the pair)
3. Runs a battery of risk heuristics against that token in parallel
4. Produces a composite risk score (0-100) with per-heuristic evidence
5. Persists to Postgres for the dashboard
6. Streams updates to a live dashboard via SSE

A live web dashboard shows the streaming feed, with one tab per heuristic and an admin panel for human review. Reviewed-legitimate tokens feed back into the metadata-similarity corpus, closing the human-in-the-loop loop.

The pipeline supports two modes:
- **Live (default)** — subscribes to live blocks via web3j WebSocket
- **Backfill** — replays a historical block range (or "last X days") through the same pipeline

---

## The heuristic stack

Three real implementations, five scaffolded stubs.

### Implemented

**1. Supply Concentration** (`SupplyConcentrationHeuristic`)
Walks `Transfer` events from the new token contract. Computes top-K holder concentration. Output: top-1 holder %, top-3 holder %, Gini coefficient.
Signal: token with 95%+ supply in 1-3 wallets after deployment is a textbook rug-pull setup.
Real-time: yes, one `eth_getLogs` call after pool creation.

**2. LP Lock + Initial Liquidity** (`LpLockHeuristic`)
Tracks the LP token's first `Transfer` event after the pool's first `Mint`. Identifies destination as: deployer wallet (high rug risk), burn address (locked, low risk), known lock contract (Unicrypt, Team Finance, Pinklock, Mudra Locker — addresses hardcoded), or unknown (yellow).
Also captures initial liquidity in USD via the WETH/USDC side of the pool.
Signal: deployer-held LP + low initial liquidity = rug pattern.

**3. Deployer History Pattern** (`DeployerHistoryHeuristic`)
The "beefy" heuristic. For the deployer EOA, enumerates all prior ERC-20 contract deployments. For each, evaluates outcome: median lifetime, fraction abandoned (<$100 final liquidity), Etherscan label (Fake_Phishing, etc.).
Signal: a serial scam-factory deployer fingerprint.

**Validation requirement:** the test fixture must hit a known Etherscan-labeled phishing factory (e.g., a `Fake_Phishing*` address with 50+ prior deployments). The heuristic should score that deployer above 0.85 with high confidence. This validation test is non-negotiable — it's what makes the heuristic credible as a real signal vs a toy.

### Stubbed (full interface scoped, implementation deferred)

**4. Metadata Similarity** (`MetadataSimilarityHeuristic`)
Spring AI's `EmbeddingClient` (OpenAI text-embedding-3-small for the demo, swappable to Workers AI bge-m3 via Spring AI's provider abstraction). pgvector cosine similarity against a curated legitimate-token corpus. Catches impersonation tokens (e.g., "USDM1.0", "uSDC").
*Stubbed because:* (a) conceptually simple — it's name-matching with embedding semantics — so the implementation budget was better invested in more sophisticated heuristics + pipeline maturity; (b) the corpus itself needs proper ingestion (see Issue #19 in ISSUES.md), not a hand-curated CSV that creates fake confidence about deployer addresses.
The interface is fully scoped. The pgvector schema (`corpus_entries`) is migrated. The Spring AI config wires up the embedding client. The corpus stays empty until Issue #19 ships; at that point the heuristic implementation is a small wrapper that becomes useful.

**5. Funding-Flow Attribution** (`FundingFlowHeuristic`)
Trace deployer EOA's funding source back N hops. Detect Tornado Cash / mixer touches, sanctioned-entity exposure, known scam-wallet patterns. Chainalysis-style attribution work.

**6. Source-Code Pattern Analysis** (`SourceCodePatternHeuristic`)
When the contract is verified on Etherscan, AST-parse the Solidity source for known scam patterns: hidden fees activated after N blocks, whitelist transfer modifiers, owner-only mint without timelock, suspicious "rescue" functions.

**7. First-N-Buyer Wallet Graph** (`FirstNBuyersHeuristic`)
For the first 20 buyers of a new token, analyze funding sources. Sybil pump = many buyer wallets funded from one source within a short window. Output: cluster signature.

**8. Social-Media Correlation** (`SocialMediaCorrelationHeuristic`)
Cross-reference token name + contract address against Telegram/X mentions in the prior hour. Yachay-shaped cross-source signal. Out-of-scope for this repo's chain-side focus but scaffolded for completeness.

Each stub implements the `RiskHeuristic` interface returning a `HeuristicResult` with `stubbed = true`. The dashboard renders a clear "STUBBED" badge for these.

---

## Architecture & pipeline maturity

This is the part that distinguishes this from a vibe demo. Each item below is non-negotiable for the tech-lead pitch.

### Ingestion ledger (the load-bearing piece)

Two-table state:

**`ingestion_runs`** — high-level execution records. Each live subscription is one run; each backfill is another run. Status (running / completed / failed), from/to block, last processed block, pools detected count.

**`processed_blocks`** — per-block ledger with PK `(block_number, block_hash)`. Survives across runs. Three purposes:
- **Idempotency:** re-processing the same `(number, hash)` is a no-op (PK conflict on insert)
- **Reorg detection:** if you see block N with hash H' when the ledger already has (N, H), mark old (N, H) as reorged, process new (N, H')
- **Resumability:** crash mid-backfill → restart skips already-processed blocks

### Watermark semantics

Since blocks are monotonically increasing, track a single contiguous-processed range scalar in a `ingestion_state` row (latest_processed_block, earliest_processed_block). Live extends forward; backfill extends backward.

**Backfill API:**
```bash
# By date range
POST /admin/backfill { "fromDate": "2025-05-08T00:00:00Z", "toDate": "2025-05-09T00:00:00Z" }
# By block range
POST /admin/backfill { "fromBlock": 22500000, "toBlock": 22510000 }
# Look-back shortcut (most common pattern)
POST /admin/backfill { "lookbackDays": 7 }
```

Date→block resolution via Etherscan API (`getBlockNoByTime`) or a local block-timestamp index.

### Reorg-aware ingestion

Block tracking by hash, not just number. On reorg detection: old `pool_detections` from the orphaned block are marked `status='reorged'` (not deleted — preserved for audit). Live processing continues with the canonical chain.

### Heuristic versioning + audit trail

Every `HeuristicResult` carries `heuristic_version` (semver). Every persisted `PoolDetection` records the version of each heuristic that produced its score, plus an `inputs_hash` over the canonical inputs to that heuristic. When the heuristic logic changes, old scores remain reproducible. Same pattern as Chainalysis's Audit Service that anchored the Bitcoin Fog Daubert hearing.

### Rate limit + caching

- **Caffeine in-memory cache** for `getDeployerHistory(eoa)` — same EOA queried within 5min returns cached result.
- **Resilience4j retry-with-backoff** for transient RPC failures.
- **Circuit breaker** if Etherscan API errors persistently — heuristics that depend on it return partial-confidence results instead of crashing.

### Observability

- **Spring Actuator + Micrometer + Prometheus** exposed at `/actuator/prometheus`.
- **Counters:** `pools_detected_total`, `heuristic_fired_total{heuristic=...}`, `errors_total{type=...}`.
- **Histograms:** `heuristic_latency_seconds{heuristic=...}`.
- **Gauges:** `latest_processed_block`, `seconds_since_last_block`.
- **Structured logging** via Logback JSON output — keys: `tx_hash`, `block_number`, `pool_address`, `score`, `heuristic`.

### Schema migrations

**Flyway** for versioned SQL migrations. Files in `src/main/resources/db/migration/`. Auto-applied on app startup. No `hibernate.ddl-auto=update` (that's a footgun in production).

### Authentication

Spring Security HTTP Basic auth. Single admin user, credentials via env vars (`ADMIN_USERNAME`, `ADMIN_PASSWORD`). Protects `/admin/**` endpoints. The live SSE feed at `/stream` can be public or protected — set via config.

### CI

GitHub Actions runs `./gradlew test` on every push to a PR or to `main`. Required status check before merge. Even for solo work, this is a tech-lead habit.

### Architecture Decision Records

`docs/adr/` folder with markdown files for major decisions:
- ADR-001: Kotlin + Spring Boot + web3j over alternative stacks
- ADR-002: Two-table ingestion ledger with watermark
- ADR-003: Composite scoring weights and calibration approach
- ADR-004: pgvector for impersonation detection corpus
- ADR-005: Three-implemented + five-stubbed heuristic balance

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
│   │   ├── HeuristicResult.kt          # data class w/ score, confidence, evidence, version, inputs_hash
│   │   ├── CompositeScorer.kt          # weighted sum, parallel via coroutines
│   │   └── heuristics/
│   │       ├── SupplyConcentrationHeuristic.kt     [IMPL]
│   │       ├── LpLockHeuristic.kt                   [IMPL]
│   │       ├── DeployerHistoryHeuristic.kt          [IMPL — with phishing-factory validation test]
│   │       ├── MetadataSimilarityHeuristic.kt       [STUB — Spring AI scaffold present]
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
│   │   └── CorpusLoader.kt             # @PostConstruct seeds CSV → pgvector
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
│   │   ├── SpringAiConfig.kt           # EmbeddingModel + VectorStore beans
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
│   │   └── legit-tokens.csv            # corpus seed (~50 known legit tokens)
│   ├── db/migration/
│   │   ├── V1__init.sql                # ingestion_runs, processed_blocks, pool_detections
│   │   ├── V2__corpus_entries.sql      # pgvector table for embeddings
│   │   ├── V3__review_decisions.sql    # admin labeling audit trail
│   │   └── V4__indexes.sql             # performance indexes
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
        │   ├── historical-scams.csv          # known scam tokens for backtest
        │   ├── historical-legitimate.csv     # known legit launches
        │   └── metadata-similarity-cases.csv # impersonation eval set
        └── fixtures/
            ├── supply-concentration-fixtures.csv
            └── lp-lock-fixtures.csv
```

---

## Build & run

### Local development

```bash
# Spin up Postgres + pgvector
docker-compose up -d

# Run migrations + boot service
./gradlew bootRun

# Or build a fat jar
./gradlew bootJar
java -jar build/libs/scanner-0.1.0.jar
```

Environment variables (defined in `application.yml` defaults; override via env or `.env`):

```
ETHEREUM_RPC_URL=wss://eth-mainnet.g.alchemy.com/v2/<KEY>
ETHERSCAN_API_KEY=<KEY>
OPENAI_API_KEY=<KEY>
ADMIN_USERNAME=admin
ADMIN_PASSWORD=<set-something>
DATABASE_URL=jdbc:postgresql://localhost:5432/scanner
DATABASE_USERNAME=scanner
DATABASE_PASSWORD=scanner
```

### Tests

```bash
./gradlew test
```

The deployer-history heuristic test MUST hit a known phishing-factory address and assert high confidence. If this test ever turns red, the heuristic logic regressed — fix before merging.

### Deploy to Fly.io

```bash
fly launch                # one-time setup
fly secrets set OPENAI_API_KEY=... ETHEREUM_RPC_URL=... ETHERSCAN_API_KEY=... ADMIN_PASSWORD=...
fly deploy
```

Provision Postgres separately:
```bash
fly postgres create --name scanner-pg
fly postgres attach scanner-pg
# pgvector extension: connect via fly proxy and CREATE EXTENSION pgvector;
```

---

## Demo narrative for the (scrubbed) call

### The opener

> *"Built a Kotlin + Spring Boot service running real-time risk surveillance on new Uniswap V3 pools. Three heuristics implemented end-to-end with production-grade pipeline discipline around them — reorg-aware ingestion, idempotent processed-block ledger, backfill mode for historical replay, heuristic versioning, structured observability via Prometheus, Caffeine-cached RPC calls with backoff, Flyway migrations, CI gates. The other five heuristics are scaffolded with the same interface and validation pattern. Wanted the pipeline discipline to be production-shaped before scaling heuristic count — brittle pipelines with many heuristics fail more often than disciplined pipelines with fewer."*

### The deployer-history "wow" moment

Validation test against a known Etherscan-labeled phishing factory:

> *"The deployer-history heuristic is validated against a known phishing factory from Etherscan's labeled set — an EOA with 89 prior contract deployments, median lifetime 28 hours, 78% abandoned. The heuristic scores it at 0.91 confidence. That kind of validation against ground truth is non-negotiable for any detection system — self-reported accuracy numbers are vibes; this is real."*

### The backfill moment

> *"Live ingestion is the default — WebSocket to the factory. But the same pipeline runs in backfill mode against any historical block range. Today I can replay May 7th's blocks and watch the heuristics fire against historical pools the same way they'd fire live. The pipeline doesn't know it's running historically — same code, different `BlockSource` implementation. Lets you test new heuristics against months of historical data, regression-test threshold changes, recover from outages."*

### The admin/corpus moment

> *"The admin panel runs a human-in-the-loop labeling workflow. Reviewers mark flagged tokens as legitimate or scam. Legitimate marks embed the token via Spring AI and upsert into the pgvector corpus. The next round of metadata-similarity scoring uses the expanded reference set. The corpus continuously improves as analysts use the system. The eval set isn't a one-time fixture — it's continuously curated."*

### The USDM1 tie-in (if conversation opens the door)

> *"Same architecture extends to USDM1 surveillance directly. The dual-regime nature — retail UBI wallets vs institutional repo collateral — is a segmentation problem, which is a cross-source attribution problem, which is exactly what this pipeline shape is built for."*

---

## CRITICAL DISCIPLINE — the Thursday lock

**Code is locked Thursday noon. No new features, no refactors, no "one more thing" after that point. Last 24 hours before Friday's call are for rest + prep brief re-reading + sleep.** The biggest predictor of how the Friday call goes is whether Alex walks in rested. Don't break this rule even if everything feels fine.

---

## Build schedule

| Day | Hours | Issues |
| --- | ---: | --- |
| Saturday | ~10 | #1 #2 #3 #4 #5 |
| Sunday | ~10 | #6 #7 #8 |
| Monday eve | ~4 | #9 #10 |
| Tuesday eve | ~5 | #11 #12 |
| Wednesday eve | ~4 | #13 #14 |
| Thursday morning | ~2 | #15 + smoke test |
| **Thursday noon onward** | — | **LOCKED — rest only** |
| Friday | — | (scrubbed) call |

Total: ~35h across 5 days. See `ISSUES.md` for the full breakdown.

---

## Stack summary (for Claude Code reference)

- **Language:** Kotlin 1.9+ on JVM 17+
- **Framework:** Spring Boot 3.2+
- **Web3:** web3j 4.12+ (auto-generated contract bindings)
- **DB:** PostgreSQL 16 with pgvector extension
- **ORM:** Spring Data JPA + Hibernate
- **Migrations:** Flyway
- **Embedding:** Spring AI (OpenAI provider for demo; Workers AI as future)
- **Vector store:** Spring AI's pgvector store
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

- Not a multi-chain scanner. Ethereum mainnet only. Cross-chain is in the "next iteration" speech, not the code.
- Not a multi-DEX scanner. Uniswap V3 only. SushiSwap, PancakeSwap, etc. are stubs in the source-discovery layer.
- Not a production-deployed product for the operator. It's an portfolio artifact built to demonstrate tech-lead pipeline thinking.
- Not a substitute for the the source project #492 challenge submission (that's a separate Python repo at `~/dev/git/market-data-challenge-492`).
- No emojis in code, comments, commits, dashboard text, or PR descriptions. Inherited from the the source project prompt-injection awareness — even though this isn't a the source project project, the no-emoji discipline is a tech-lead habit worth maintaining.

---

## Where to start (Claude Code, day 1)

1. Read this entire CLAUDE.md (you just did).
2. Open `ISSUES.md` — that's the day-by-day ticket breakdown.
3. Open Issue #1 and start scaffolding. Don't deviate from the architecture described above without flagging the deviation explicitly in a commit message.
4. After each issue: commit, push, watch CI pass. Move to next.
5. Stop at Thursday noon regardless of state.
