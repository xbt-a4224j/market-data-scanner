# ADR-005: Three-implemented + five-stubbed heuristic balance

**Status:** Accepted

## Context

The original ticket plan had eight heuristics. The realistic implementation budget for the demo timeline was three. Two ways to spend the budget:

1. **Implement all eight shallowly.** Each heuristic gets a token implementation that covers the happy path but skips the hard cases.
2. **Implement three deeply, scaffold the other five with the same interface.** Three heuristics are battle-tested with fixtures, edge cases, validation tests, and real on-chain plumbing. Five are interface-only, returning `confidence = 0` so the composite scorer ignores them.

## Decision

**Three implemented + five stubbed.** Specifically:

### Implemented (real on-chain reads, scoring logic, fixture tests)

- `SupplyConcentrationHeuristic` — walks Transfer events, computes top-K percentages + Gini coefficient, exclusion of burn-sink recipients
- `LpLockHeuristic` — reads first Mint event, classifies position-NFT owner (deployer / burned / locked / NPM / unknown), values initial liquidity in USD
- `DeployerHistoryHeuristic` — pulls full prior-contract deployment history from Etherscan, scores on count + densest 24h burst. Validation test against a real mainnet phishing factory (`0xf24246e0d5399ea85dbdadcfdbc9e8f14490db58`, 246 prior deployments) asserts score >= 0.85 with high confidence — non-negotiable.

### Stubbed (interface scoped, returns neutral, dashboard renders the planned signal)

- `MetadataSimilarityHeuristic` — implemented in Issue #19 with OpenAI embeddings + pgvector cosine search. Was originally stubbed for cost reasons; the upgrade landed once the corpus pipeline was specified.
- `FundingFlowHeuristic` — Tornado/mixer attribution. Needs a multi-hop trace, complex.
- `SourceCodePatternHeuristic` — Solidity AST scan for known rug patterns. Needs Etherscan verified-source pulls + a Solidity parser.
- `FirstNBuyersHeuristic` — Sybil-pump funding-source clustering. Needs first-20-buyer enumeration + funding trace.
- `SocialMediaCorrelationHeuristic` — out of chain-side scope; scaffolded for completeness.

All eight implement the same `RiskHeuristic` interface. Stubs extend a shared `StubHeuristic` base that returns `score = 0.5, confidence = 0.0, evidence = {stubbed: true, plannedSignal: "..."}, stubbed = true`. The dashboard's per-heuristic tab renders the planned-signal text + a "STUBBED" badge prominently.

## Consequences

**Positive:**

- The three implemented heuristics are deep enough to defend against scrutiny. The deployer-history validation test against a real EOA with 246 prior deployments scoring >= 0.85 is the centerpiece — that single assertion makes the heuristic credible vs. "self-reported accuracy" vibes.
- The stubs serve as a roadmap, not noise. The dashboard literally renders the planned-signal text on each stub's tab so a reviewer can see what the heuristic WILL do.
- The composite scorer's confidence-weighted formula (ADR-003) means stubs do not pollute the score — they contribute zero numerator and zero denominator.
- Adding a real implementation is mechanical: replace `: StubHeuristic(...)` with `: RiskHeuristic` and implement `evaluate()`. The interface and DI wiring are already there. Issue #19 was exactly this swap.

**Negative:**

- Five-of-eight stubbed is a lot of "promised but not delivered" surface. Mitigated by the dashboard being explicit about it (STUBBED badges everywhere) and CLAUDE.md naming each by signal so a reviewer can prioritize which to implement next.
- The composite weights for stubbed heuristics are pinned at zero in `application.yml`. If we forget to bump the weight when implementing one, the new heuristic ships dark. This has happened once during development and was caught by the per-pool detail page showing the heuristic firing but not contributing — a manual review checkpoint, not an automated one.

## Alternatives considered

- **All-eight-shallow.** Rejected — the deployer-history validation against a real phishing factory was the demo centerpiece. Spreading the budget across eight shallow implementations would have meant zero of them have a defensible accuracy claim.
- **Three implemented + zero stubs (rip out the five).** Rejected — the five-stub roadmap is itself a deliverable. It signals "this is where the system is going" without committing to ship by the demo. Removing them would have left CLAUDE.md and the architecture diagram with eight slots and the code with three.
