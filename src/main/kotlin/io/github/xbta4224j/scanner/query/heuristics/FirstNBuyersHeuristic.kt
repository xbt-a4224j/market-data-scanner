package io.github.xbta4224j.scanner.query.heuristics

import org.springframework.stereotype.Component

/**
 * STUB - implementation deferred.
 *
 * Will analyze the funding sources of the first 20 buyers of a new token.
 * Sybil pump pattern = many buyer wallets funded from a single source within
 * a short window. Output: cluster signature.
 */
@Component
class FirstNBuyersHeuristic : StubHeuristic(
    name = "first_n_buyers",
    plannedSignal = "Funding-source clustering across the first 20 buyers (Sybil pump detection).",
)
