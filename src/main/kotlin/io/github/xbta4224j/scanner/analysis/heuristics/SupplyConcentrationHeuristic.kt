package io.github.xbta4224j.scanner.analysis.heuristics

import org.springframework.stereotype.Component

/**
 * STUB - real implementation lands in Issue #5.
 *
 * Will walk Transfer events from the token contract via `eth_getLogs` and
 * compute top-1 / top-3 / top-10 holder percentages plus the Gini coefficient.
 * High concentration in a single deployer-controlled wallet shortly after
 * deployment is a textbook rug-pull setup.
 */
@Component
class SupplyConcentrationHeuristic : StubHeuristic(
    name = "supply_concentration",
    plannedSignal = "Top-K holder concentration (top-1, top-3, Gini) shortly after deployment.",
)
