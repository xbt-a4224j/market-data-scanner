# Seed data sources and methodology

This document explains where the entries in [`src/test/resources/eval/known-legit-tokens.csv`](../src/test/resources/eval/known-legit-tokens.csv) and [`src/test/resources/eval/known-scam-tokens.csv`](../src/test/resources/eval/known-scam-tokens.csv) came from, the criteria for inclusion, and the limitations of each source. The CSVs are consumed by Issue #14's `BacktestRunner` to compute the scanner's precision and recall.

The CSVs themselves are produced by [`scripts/curate-seed-data.py`](../scripts/curate-seed-data.py). Re-running that script regenerates both CSVs deterministically.

## Why this exists as a separate ticket

The original Issues breakdown bundled "find ground-truth tokens" and "build the backtest harness" into a single ticket (#14). Splitting curation out (Issue #20) forces the sourcing methodology to be deliberate and documented rather than buried inside test setup. The precision/recall numbers in #14 only mean something if the seed dataset they're computed against is itself defensible.

## Volume

| set | rows | source pipeline |
| --- | --- | --- |
| `known-legit-tokens.csv` | 142 | CoinGecko `/coins/markets` top-250 by USD market cap, intersected with `/coins/list?include_platform=true` to get the Ethereum mainnet contract address per project, then verified contract-by-contract via Etherscan V2 `getContractCreation`. |
| `known-scam-tokens.csv` | 142 | MyEtherWallet community-curated address darklist (`addresses-darklist.json`), filtered to the subset that is an actual contract deployment (rather than a phishing-collector EOA) by checking each via Etherscan V2 `getContractCreation`. |

Both CSVs are at the same scale (~142 each). Every row carries a verified `deployer_address`, `deployment_block`, and ISO `deployment_ts`, all sourced from the canonical Etherscan response - not estimated, not hand-typed.

## Legit-token sourcing (`known-legit-tokens.csv`)

### Source

- **CoinGecko `/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=250`** for the top-250 cryptocurrencies by USD market cap at the time the script was run.
- **CoinGecko `/coins/list?include_platform=true`** to map each project to its Ethereum-mainnet contract address (where one exists). Many top-cap cryptocurrencies are L1 tokens (Bitcoin, Ethereum itself, BNB Beacon, Solana, Tron, ...), so only a subset of the top-250 produces a usable Ethereum contract row. The script keeps only rows where `platforms.ethereum` is set.
- **Etherscan V2 `getContractCreation`** to verify each contract address actually exists on-chain and to pull its canonical deployer + deployment block + deployment timestamp.

### Inclusion criteria

A row makes the legit CSV iff:

1. The project sits in CoinGecko's top-250 by market cap (signals "established", not "still earning trust").
2. CoinGecko reports an Ethereum mainnet contract address for it.
3. Etherscan returns a successful `getContractCreation` for that address (filters out invalid entries).

There is no manual curation past that. If CoinGecko's top-250 changes, the script regenerates a different set the next time it runs - that is intentional, the corpus should track market reality.

### Categorization

The script's `categorize()` function classifies each token into one of: `stablecoin`, `wrapped_or_lst`, `meme_established`, `governance`, `bluechip_defi`. The category mapping is symbol-based (e.g. `usdc`, `usdt`, `dai` -> `stablecoin`), so a heuristic list rather than an authoritative one. It is fine for the dashboard's grouping; not load-bearing for any heuristic's correctness.

### Known limitations

- A token's _market cap rank_ does not strictly mean it is _legitimate_. SHIB and PEPE are in the corpus by virtue of being top-cap; they were also subjects of pump-and-dump cycles. The corpus is "established projects whose contract code is real and tradable on Uniswap", not "projects we endorse".
- L1 tokens that have wrapped versions on Ethereum (e.g. `BNB`, `MATIC`) appear here. Those wrapped versions are real ERC-20 contracts, but their canonical-deployer story is the wrapping bridge, not the L1 project.
- The script does NOT call `symbol()` / `name()` against the contract to verify it returns what CoinGecko advertises. That additional check is part of Issue #19's full ingestion pipeline; for the seed CSV, the CoinGecko-Etherscan join is treated as authoritative.

## Scam-token sourcing (`known-scam-tokens.csv`)

### Source

- **MyEtherWallet community darklist** at `https://raw.githubusercontent.com/MyEtherWallet/ethereum-lists/master/src/addresses/addresses-darklist.json` - a long-running, community-curated list of addresses MEW (and downstream wallets) flag as malicious. 715 entries at fetch time; 652 unique after lowercasing.
- **Etherscan V2 `getContractCreation`** to filter the darklist down to entries that are actually deployed contracts (the rest are phishing-collector EOAs - addresses where stolen funds were aggregated, not deployed code). Of 652 unique addresses, 142 are contracts.

### `scam_type` taxonomy

The script's `classify()` function tags each row from the darklist's free-text `comment` field. Buckets:

- `impersonation` - "Fake X token / coin / ICO / presale" patterns. Most populated bucket among non-empty comments.
- `phishing_factory` - explicit "phishing" mentions in the comment.
- `honeypot`, `ponzi`, `rug_pull`, `exploit`, `other_scam` - matched against keyword regex; sparse in the MEW source.
- `darklisted` - the address is in the source darklist but the `comment` field is empty (122 rows). These are flagged-without-explanation entries; the heuristic is "if MyEtherWallet's darklist maintainers blacklisted it, treat it as adversarial training data".

### Known limitations

This is the largest gap in the dataset and the strongest argument for Issue #19:

- **Bias toward 2017-2019 ICO-era scams.** Most darklist entries are from the impersonation-token wave during the 2017-2018 ICO boom. The dataset is thin on 2020+ DeFi-era scams (rug pulls on Uniswap V2/V3, honeypot tokens, retroactive-airdrop drainers).
- **Bias toward impersonation over rug pulls.** The MEW darklist has a phishing-prevention focus. Pure rug pulls (token deployer creates LP, locks supply, dumps, walks away) are underrepresented. Better sources for that pattern are de.fi's rugged-projects database, ChainAbuse rug-pull category, and Chainalysis Reactor exports - none of which are freely API-accessible.
- **No sanctioned-entity coverage.** OFAC SDN list addresses (Tornado Cash relayers, Lazarus wallets) are not in this dataset. Most are EOAs anyway, but some have token deployments that would matter for the deployer-history heuristic. Issue #19's pipeline should pull SDN data from public OFAC feeds.
- **122 of 142 rows are `darklisted` with no comment.** They are real Etherscan-verified contracts that MEW's maintainers flagged, but the rationale is not in the source. They are still useful as adversarial samples - the deployer-history heuristic, supply-concentration heuristic, and LP-lock heuristic should fire on them - but they cannot be cleanly bucketed by `scam_type`.

### What Issue #19 should add

When the proper corpus ingestion pipeline ships:

1. Pull additional scam sources: ChainAbuse API, de.fi rugged-projects DB, OFAC SDN feed, CryptoScamDB API, Forta Network bot alerts.
2. For each candidate, `getContractCreation` + `eth_call symbol()` + `eth_call name()` to confirm it is a deployed ERC-20.
3. Cross-reference deployer EOAs across sources - a deployer flagged by 3+ independent sources should be treated as high-confidence adversarial.
4. Schedule weekly re-ingestion to keep the corpus fresh as new scams emerge.

## Reproducibility

The CSVs are not hand-edited. To regenerate:

```bash
export ETHERSCAN_API_KEY=<your-key>
python3 scripts/curate-seed-data.py
```

Etherscan free-tier rate limit is 5 calls/second; the script sleeps `200 ms` between batches. Total runtime: ~3 minutes for the legit pass + ~3 minutes for the scam pass. CoinGecko free-tier rate limits permitting (you may hit 429 on rapid re-runs).
