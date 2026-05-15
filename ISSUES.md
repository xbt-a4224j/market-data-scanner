# GitHub Issues - Market Data Scanner

Phase-by-phase issue breakdown. Each issue is scoped to ~1-6h. Dependencies noted. Bulk-create via `gh issue create` once the repo is set up.

---

## PHASE 1 - foundation

### Issue #1: Project scaffold + Postgres dev environment

**Labels:** `infra`, `priority:1`

**Description:**
Spring Boot 3.2+ Kotlin project skeleton with all dependencies wired. Docker-compose for local Postgres + pgvector. Flyway for schema migrations. Bootable `./gradlew bootRun` that starts cleanly.

**Acceptance criteria:**
- [ ] `build.gradle.kts` with all dependencies (Spring Boot, web3j, Spring AI, Flyway, Caffeine, Resilience4j, Spring Security, Actuator, etc.)
- [ ] `docker-compose.yml` brings up pgvector-enabled Postgres on port 5432
- [ ] `application.yml` with sensible defaults + env-var overrides
- [ ] Flyway baseline migration `V1__init.sql` creates `ingestion_runs`, `processed_blocks`, `pool_detections` tables
- [ ] `./gradlew bootRun` boots without errors; `/actuator/health` returns UP
- [ ] `./gradlew test` runs (even if empty)

**Estimate:** 4h
**Depends on:** —

---

### Issue #2: Heuristic framework + composite scorer

**Labels:** `analysis`, `priority:1`

**Description:**
Define the `RiskHeuristic` interface, `HeuristicResult` data class (with score, confidence, evidence, heuristic_version, inputs_hash), and `CompositeScorer` that runs heuristics in parallel via coroutines and produces a weighted composite score.

**Acceptance criteria:**
- [ ] `RiskHeuristic` interface in `analysis/`
- [ ] `HeuristicResult` data class with all required fields including version + inputs_hash
- [ ] `CompositeScorer` accepts a `List<RiskHeuristic>`, runs them in parallel, returns composite score 0-100
- [ ] Eight empty stub classes in `analysis/heuristics/`, each returning `HeuristicResult(score=0.5, confidence=0.0, evidence={"stubbed": true}, stubbed=true)`
- [ ] Unit test confirming composite scorer's weighted average is correct
- [ ] Stubs are registered as Spring beans

**Estimate:** 1.5h
**Depends on:** #1

---

### Issue #3: Factory listener (LiveBlockSource)

**Labels:** `chain`, `priority:1`

**Description:**
web3j WebSocket subscription to Uniswap V3 factory contract (`0x1F98431c8aD98523631AE4a59f267346ea31F984`). Decodes `PoolCreated` events. Emits `TokenContext` records into the ingestion pipeline.

**Acceptance criteria:**
- [ ] `BlockSource` sealed interface defined
- [ ] `LiveBlockSource` implementation using `web3j.blockFlowable(true).asFlow()`
- [ ] `PoolCreatedDecoder` extracts the non-WETH/USDC/USDT/DAI token from each pool event
- [ ] `TokenContext` data class captures: token address, deployer, pool address, paired-with-address, block, tx-hash
- [ ] Logs every new pool detection with structured fields
- [ ] Runs end-to-end against mainnet RPC (Alchemy or Infura free tier; URL via env var)
- [ ] No persistence yet — just log

**Estimate:** 3h
**Depends on:** #1, #2

---

### Issue #4: Persistence layer

**Labels:** `persistence`, `priority:1`

**Description:**
JPA entities + Spring Data repositories for `PoolDetection`, `IngestionRun`, `ProcessedBlock`. Flyway migrations define the schema. Compound PK on `ProcessedBlock(block_number, block_hash)`.

**Acceptance criteria:**
- [ ] `PoolDetection` entity captures token, pool, deployer, score, evidence (jsonb), per-heuristic version map, detected_at
- [ ] `IngestionRun` entity per CLAUDE.md schema
- [ ] `ProcessedBlock` entity with composite PK
- [ ] Repositories with custom queries for the dashboard (e.g., `findTop50ByOrderByDetectedAtDesc`)
- [ ] `WatermarkService` exposes `getLatestProcessed()` / `getEarliestProcessed()` / `extendForward(block)` / `extendBackward(block)`
- [ ] Unit tests using Testcontainers Postgres
- [ ] `ProcessedBlockRepository.existsByBlockNumberAndBlockHash()` for idempotency check

**Estimate:** 2h
**Depends on:** #1

---

### Issue #5: SupplyConcentrationHeuristic — IMPLEMENTED

**Labels:** `heuristic`, `priority:1`

**Description:**
Walk `Transfer` events from the new token contract. Compute top-K holder distribution. Output: top-1 holder %, top-3 holder %, total holders, Gini coefficient.

**Acceptance criteria:**
- [ ] Implementation walks Transfer events via `eth_getLogs` (single call after pool creation)
- [ ] Computes top-1 %, top-3 %, top-10 %, Gini coefficient
- [ ] Returns HeuristicResult with score normalized 0-1 (where 0.99 supply concentration in top-1 → score 0.95)
- [ ] Evidence map includes all the raw numbers for dashboard display
- [ ] Unit tests with synthetic fixtures (clean distribution → low score, concentrated → high score)
- [ ] Tests live in `src/test/resources/fixtures/supply-concentration-fixtures.csv`

**Estimate:** 2h
**Depends on:** #2, #3

---

## PHASE 2 - heuristics + ingestion

### Issue #6: LpLockHeuristic — IMPLEMENTED

**Labels:** `heuristic`, `priority:1`

**Description:**
Track the LP token's destination after the pool's first Mint. Classify into: deployer-held (high rug risk), burned, locked (Unicrypt/Team Finance/Pinklock/Mudra), unknown. Combined with initial liquidity in USD.

**Acceptance criteria:**
- [ ] Hardcoded set of known lock contract addresses
- [ ] Burn-address detection (0x0...0 and 0x...dead)
- [ ] Computes initial liquidity in USD from the WETH/USDC side (use Etherscan-style price query or pool's sqrtPriceX96)
- [ ] Returns HeuristicResult with composite score (deployer-held + thin liquidity = high score)
- [ ] Unit tests with each LP destination scenario
- [ ] Fixtures in `src/test/resources/fixtures/lp-lock-fixtures.csv`

**Estimate:** 2.5h
**Depends on:** #2, #3

---

### Issue #7: DeployerHistoryHeuristic — IMPLEMENTED with phishing-factory validation

**Labels:** `heuristic`, `priority:1`, `the-big-one`

**Description:**
The most sophisticated implemented heuristic. For each token's deployer EOA, enumerate all prior ERC-20 contract deployments. Score each prior token's outcome (lifetime, final liquidity, Etherscan label). Aggregate into a "deployer reputation" score.

**Acceptance criteria:**
- [ ] `getDeployerHistory(eoa)` finds all prior contract creations by that EOA via `eth_getLogs`
- [ ] For each prior token, evaluate: lifetime (deployment ts → last Transfer ts), final liquidity, Etherscan label
- [ ] Caffeine cache wraps the call (5-min TTL — same EOA in a session returns cached)
- [ ] Composite score: weighted sum of (abandonment rate, median lifetime under 48h, Etherscan phishing flags / total)
- [ ] **VALIDATION TEST (non-negotiable):** test hits a known Etherscan-labeled phishing factory with 50+ deployments. Expected score > 0.85 with high confidence. Pick a stable EOA (one that won't get unlabeled). Document the chosen address in the test.
- [ ] Test runs in CI without making real Etherscan calls (mock the response or seed test data)
- [ ] Returns null when deployer is brand-new (no prior deployments) → confidence 0, score 0.4 (mildly suspicious)

**Estimate:** 6h
**Depends on:** #2, #3, #4

**Notes:** This is the heuristic that makes the demo not-a-vibe-demo. Spend the time on this one. The validation test is what makes the heuristic credible.

---

### Issue #8: Ingestion pipeline + BackfillBlockSource

**Labels:** `ingestion`, `priority:1`

**Description:**
The shared `IngestionPipeline` that consumes any `BlockSource` and writes to persistence. Plus the `BackfillBlockSource` that iterates a historical block range, skipping already-processed blocks.

**Acceptance criteria:**
- [ ] `IngestionPipeline.run(source: BlockSource)` consumes the flow, checks idempotency, runs scorers, persists
- [ ] Reorg detection: when block N is seen with hash H' but ledger has (N, H), mark old as reorged, process new
- [ ] `BackfillBlockSource(fromBlock, toBlock)` constructor; queries `processed_blocks` to skip done blocks
- [ ] Date→block resolution helper using Etherscan API (`getBlockNoByTime`)
- [ ] `IngestionRun` records created and updated per execution
- [ ] Tests cover: live-mode happy path, backfill-mode happy path, reorg detection, restart after crash (resumability)

**Estimate:** 4h
**Depends on:** #3, #4

---

## PHASE 3 - dashboard surface

### Issue #9: SSE endpoint + Overview tab

**Labels:** `dashboard`, `priority:2`

**Description:**
Server-Sent Events stream emitting new pool detections. Overview tab dashboard HTML (Thymeleaf + htmx). Live feed updates without page refresh.

**Acceptance criteria:**
- [ ] `/stream` SSE endpoint emits JSON per pool detection
- [ ] Thymeleaf layout `layout.html` with the tab bar
- [ ] Overview tab `overview.html` with live feed table consuming the SSE stream via htmx
- [ ] Stats strip with: pools-last-hour count, %-flagged-high, highest-score-last-hour, heuristics-implemented count
- [ ] No JS build step — htmx via CDN script tag

**Estimate:** 3h
**Depends on:** #3, #4

---

### Issue #10: Per-heuristic dashboard tabs

**Labels:** `dashboard`, `priority:2`

**Description:**
Eight tabs total — three implemented heuristics get detail views, five stubbed ones get clear "STUBBED" placeholder pages with the interface contract documented.

**Acceptance criteria:**
- [ ] Supply Concentration tab: shows recent pools sorted by concentration, with top-K holder breakdown for a selected pool
- [ ] LP Lock tab: shows recent pools grouped by LP destination (pie chart via Chart.js for the breakdown)
- [ ] Deployer History tab: shows recent pools with deployer history badges (e.g., "89 prior deployments, 78% abandoned")
- [ ] Five stubbed tabs each render: the heuristic interface contract, the planned signal, the "STUBBED" badge prominently

**Estimate:** 3h
**Depends on:** #5, #6, #7, #9

---

## PHASE 4 - admin panel

### Issue #11: Admin panel — review queue + corpus auto-add

**Labels:** `admin`, `priority:2`

**Description:**
The human-in-the-loop labeling workflow. Reviewers see flagged pools, mark as legitimate/scam/defer. Legitimate marks embed and insert into pgvector corpus. Scam marks register a deployer reputation hit.

**Acceptance criteria:**
- [ ] `/admin/queue` endpoint returns flagged pools awaiting review (score > 70, no decision yet)
- [ ] `/admin/review/{poolId}` accepts POST with `{label, notes}`
- [ ] When `label = LEGITIMATE`: token's metadata gets embedded via Spring AI and upserted into pgvector corpus
- [ ] When `label = SCAM`: deployer EOA gets added to a reputation-penalty list (consulted by DeployerHistory)
- [ ] `/admin/queue` Thymeleaf view renders the queue with action buttons (htmx posts)
- [ ] `review_decisions` table records every label for audit
- [ ] All admin endpoints behind `@PreAuthorize("hasRole('REVIEWER')")` (wired in Issue #13)

**Estimate:** 4h
**Depends on:** #4, #9, #10

---

### Issue #12: Admin panel — backfill trigger + run status

**Labels:** `admin`, `priority:2`

**Description:**
Manual backfill trigger via the admin panel. Three modes: date range, block range, lookback-days. Shows active ingestion runs with progress.

**Acceptance criteria:**
- [ ] `POST /admin/backfill` accepts `{fromDate, toDate}` OR `{fromBlock, toBlock}` OR `{lookbackDays}`
- [ ] Spawns a `BackfillBlockSource` and runs it in a background coroutine
- [ ] `GET /admin/ingestion-runs` returns all runs (active + recent)
- [ ] Admin UI page with: trigger form (with all three input modes), active runs list with progress bars, completed runs history
- [ ] Watermark display: "earliest processed block: N, latest processed block: M"

**Estimate:** 2h
**Depends on:** #8, #11

---

## PHASE 5 - hardening

### Issue #13: Spring Security basic auth

**Labels:** `security`, `priority:2`

**Description:**
HTTP Basic auth protecting `/admin/**`. Single admin user. Credentials from env vars. SSE stream + dashboard remain public for the demo.

**Acceptance criteria:**
- [ ] `SecurityConfig` configures filter chain with HTTP basic auth on `/admin/**`
- [ ] Public access to: `/`, `/stream`, `/heuristics/**`, `/actuator/health`, `/actuator/prometheus`
- [ ] Admin user provisioned via `ADMIN_USERNAME` + `ADMIN_PASSWORD` env vars
- [ ] BCrypt password encoding (don't store cleartext)
- [ ] 401 response for unauthenticated admin requests
- [ ] Integration test: hitting `/admin/queue` without auth returns 401; with correct creds returns 200

**Estimate:** 1h
**Depends on:** #11

---

### Issue #14: Eval CSV seeds + backtest runner

**Labels:** `eval`, `priority:2`

**Description:**
Curated test fixtures for historical scams and legitimate launches. A backtest harness that replays each historical token through the pipeline and reports precision/recall.

**Acceptance criteria:**
- [ ] `src/test/resources/eval/historical-scams.csv` with 5-10 known scam tokens (token address, deployer, deployment block, outcome label, source)
- [ ] `src/test/resources/eval/historical-legitimate.csv` with 5-10 known good launches
- [ ] `BacktestRunner` test class that runs each historical token through the pipeline using a stub `BlockSource`, asserts heuristic scores match expectations
- [ ] Per-test output: precision (TP / TP+FP), recall (TP / TP+FN), F1
- [ ] Test passes when overall precision > 0.85 AND recall > 0.80
- [ ] Numbers logged to console for visibility

**Estimate:** 3h
**Depends on:** #5, #6, #7

---

### Issue #15: GitHub Actions CI

**Labels:** `infra`, `priority:3`

**Description:**
Run `./gradlew test` on every push to a PR. Required status check before merge.

**Acceptance criteria:**
- [ ] `.github/workflows/test.yml` runs on push and pull_request
- [ ] Java 17 + Gradle setup
- [ ] Postgres service in CI (Testcontainers handles the Postgres needs at test time)
- [ ] `./gradlew test` runs and fails the workflow on test failure
- [ ] Required status check enabled on `main` branch (do via GitHub UI after merge)

**Estimate:** 30min
**Depends on:** #1

---

## PHASE 5 (continued)

### Issue #16: Observability — Prometheus metrics + structured logging

**Labels:** `observability`, `priority:2`

**Description:**
Custom Micrometer metrics for the heuristics + pipeline. Logback JSON output for log aggregation.

**Acceptance criteria:**
- [ ] Counter `pools_detected_total`
- [ ] Counter `heuristic_fired_total{heuristic=<name>, outcome=<flagged|clean>}`
- [ ] Histogram `heuristic_latency_seconds{heuristic=<name>}`
- [ ] Counter `errors_total{type=<rpc|etherscan|db|...>}`
- [ ] Gauge `latest_processed_block`
- [ ] Gauge `seconds_since_last_block`
- [ ] `/actuator/prometheus` exposes all of the above
- [ ] Logback logs as JSON with `tx_hash`, `block_number`, `pool_address`, `score`, `heuristic` fields

**Estimate:** 1.5h
**Depends on:** #1, #5, #6, #7

---

## PHASE 6 - deploy

### Issue #17: Deploy to Fly.io

**Labels:** `deploy`, `priority:1`

**Description:**
Deploy the service to Fly.io. Provision managed Postgres with pgvector. Configure secrets. Confirm end-to-end production smoke test.

**Acceptance criteria:**
- [ ] `fly.toml` with appropriate VM size (smallest tier should suffice)
- [ ] `Dockerfile` for the JVM service (OpenJDK 17 base image)
- [ ] Fly Postgres provisioned with pgvector extension
- [ ] All secrets set via `fly secrets set`
- [ ] Service accessible at the Fly URL with auth working
- [ ] Live block subscription functioning (verify by checking `/admin/ingestion-runs`)
- [ ] At least one new pool detected and rendered in the dashboard

**Estimate:** 2h
**Depends on:** #1 through #16

---

### Issue #18: Pre-deploy smoke test

**Labels:** `qa`, `priority:1`

**Description:**
Final end-to-end test of the deployed system. Walk through every surface and fix any visible bugs.

**Acceptance criteria:**
- [ ] Dashboard loads cleanly at the deployed URL
- [ ] At least one new pool detection is visible in the live feed
- [ ] All three implemented heuristic tabs render expected content
- [ ] All five stubbed tabs show the "STUBBED" badge cleanly
- [ ] Admin auth challenges work (basic auth prompt appears)
- [ ] Backfill from the admin panel triggers a run and updates the UI
- [ ] Per-heuristic detail page (click into a row) renders evidence correctly
- [ ] Prometheus endpoint at `/actuator/prometheus` returns counters
- [ ] Run `./gradlew test` once more locally — all green
- [ ] All surfaces verified end-to-end against the deployed instance

**Estimate:** 1h
**Depends on:** #17

---

## Issue grouping for `gh issue create` bulk creation

Save this as `issues.json` or feed via a script:

```bash
gh label create infra --color "0052cc"
gh label create analysis --color "5319e7"
gh label create heuristic --color "5319e7"
gh label create chain --color "008672"
gh label create persistence --color "008672"
gh label create dashboard --color "fbca04"
gh label create admin --color "fbca04"
gh label create security --color "d93f0b"
gh label create observability --color "0e8a16"
gh label create eval --color "0e8a16"
gh label create deploy --color "d93f0b"
gh label create qa --color "d93f0b"
gh label create ingestion --color "008672"
gh label create priority:1 --color "b60205"
gh label create priority:2 --color "fbca04"
gh label create priority:3 --color "0e8a16"
gh label create the-big-one --color "ff6b6b"
```

Then create each issue from this file with `gh issue create --title "..." --body-file ..." --label "..."`.

---

## FUTURE WORK

### Issue #19: Corpus Ingestion Pipeline — legitimate token + deployer ground truth

**Labels:** `corpus`, `priority:3`, `future-work`

**Description:**
Properly populate the legitimate-token corpus used by the metadata-similarity heuristic. This is intentionally a separate piece of work because curating ground-truth data correctly is non-trivial and shouldn't be hacked together in a CSV.

The metadata-similarity heuristic remains stubbed until this issue ships. The composite scorer's weight for that heuristic stays at 0.15 in `application.yml` so the architecture is intact, but at runtime the heuristic returns a 0-confidence neutral result that contributes nothing meaningful.

**Acceptance criteria:**
- [ ] Pipeline ingests legitimate-token records from authoritative sources:
  - CoinGecko top-N tokens by market cap (their API has a JSON listing endpoint)
  - Etherscan's labeled-contracts dataset (canonical token addresses with verified-source status)
  - Any internal labeled-legitimate set you may have access to (some attribution-data vendors publish positive-label lists alongside their sanctioned-entity lists)
- [ ] For each candidate token, verify on-chain:
  - The contract address has code (is a contract, not an EOA)
  - The `symbol()` / `name()` calls return values matching the source
  - The contract was deployed before some cutoff (filter out brand-new entries that haven't earned trust yet)
- [ ] Resolve the canonical deployer address via Etherscan's `getContractCreation` API endpoint
- [ ] Embed each row via Spring AI's `EmbeddingClient` and upsert into the `corpus_entries` table
- [ ] Run as a scheduled job (weekly) to pick up new tokens that have entered the trusted set
- [ ] Admin-panel "Mark Legitimate" actions feed into the same table with `source='admin_review'` to differentiate from `source='coingecko_top_500'` etc.
- [ ] Manual override list for tokens that need to be in the corpus but aren't auto-discoverable (USDM1 once it's live, federal-issued tokens, etc.)

**Estimate:** 8-12h depending on how many sources you wire up. Future work.

**Notes:**
The temptation to seed a small CSV at startup is real but creates fake confidence — a corpus that says "USDC's deployer is 0xabc" when 0xabc is wrong silently breaks impersonation detection. Better to keep the heuristic stubbed and the corpus empty until the proper ingestion pipeline lands than to ship a corpus you can't defend the contents of. This is the discipline that separates real attribution work from vibes.

---

