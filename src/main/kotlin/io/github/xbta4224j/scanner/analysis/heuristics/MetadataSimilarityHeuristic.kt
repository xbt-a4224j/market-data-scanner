package io.github.xbta4224j.scanner.analysis.heuristics

import org.springframework.stereotype.Component

/**
 * STUB - implementation deferred to Issue #19 (corpus ingestion pipeline).
 *
 * Will embed token name + symbol + description via Spring AI and run pgvector
 * cosine similarity against the legitimate-token corpus. Catches impersonation
 * tokens (e.g. "USDM1.0", "uSDC", "USDC2"). Stays stubbed until Issue #19
 * ships the proper corpus ingestion - a hand-curated CSV would create fake
 * confidence (rows that say "USDC's deployer is 0xabc" when 0xabc is wrong
 * silently break the heuristic).
 */
@Component
class MetadataSimilarityHeuristic : StubHeuristic(
    name = "metadata_similarity",
    plannedSignal = "Embedding cosine-similarity between token metadata and curated legitimate-token corpus (impersonation detection).",
)
