# market-data-scanner

Real-time risk-surveillance pipeline for new Uniswap V3 pools on Ethereum mainnet. Built in Kotlin + Spring Boot + web3j with Postgres + pgvector for storage and Spring AI for embedding-based heuristics.

See [`CLAUDE.md`](CLAUDE.md) for the full architecture context and [`ISSUES.md`](ISSUES.md) for the issue breakdown.

## Quick start

```bash
# Spin up Postgres + pgvector (port 5433 on host)
docker compose up -d

# Set env vars (or put in a .env file - already gitignored)
export INFURA_API_KEY="<your-infura-project-id>"      # or set ETHEREUM_RPC_URL / ETHEREUM_WS_URL directly
export ETHERSCAN_API_KEY="<your-etherscan-key>"
export ANTHROPIC_API_KEY="<your-anthropic-key>"        # used by admin/corpus + per-pool evidence summaries
export ADMIN_USERNAME="admin"
export ADMIN_PASSWORD="<set-something-real>"

# Boot
./gradlew bootRun

# Tests
./gradlew test
```

Open <http://localhost:8080> for the dashboard. <http://localhost:8080/admin/queue> for the review queue (basic auth).

## Legitimate-token corpus

The metadata-similarity heuristic's corpus is **not** seeded from a CSV. Corpus population is its own piece of work - see Issue #19 in [`ISSUES.md`](ISSUES.md).

Until that ticket ships, the corpus remains empty and the metadata-similarity heuristic stays stubbed. The composite scorer's weight for it in [`application.yml`](src/main/resources/application.yml) is preserved (0.15) but the runtime returns a neutral 0-confidence result that contributes nothing meaningful.

Why a separate ticket: a hand-curated CSV of "legitimate tokens with their canonical deployers" creates fake confidence. A corpus row that says "USDC's deployer is 0xabc" when 0xabc is wrong silently breaks impersonation detection. The ingestion has to be sourced from authoritative places (CoinGecko top-N by market cap, Etherscan's verified-contract labels, Etherscan's `getContractCreation` for deployer resolution) and verified on-chain (`symbol()` and `name()` calls matching the source). That's a real engineering task, not a fixture. Keep the heuristic stubbed and the corpus empty rather than ship a corpus you can't defend.

When Issue #19 lands, the admin panel's "Mark Legitimate" action (Issue #11) is the secondary ingestion path: human review adds rows with `source='admin_review'` to differentiate from the bulk-ingested `source='coingecko_top_500'` etc.

## Layout

See [`CLAUDE.md`](CLAUDE.md) for the full directory tree, dependency stack, and per-component contracts.

## Deploy

See [`CLAUDE.md`](CLAUDE.md) -> "Deploy to Fly.io" section.
