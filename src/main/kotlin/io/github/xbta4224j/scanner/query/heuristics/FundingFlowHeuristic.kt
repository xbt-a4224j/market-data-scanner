package io.github.xbta4224j.scanner.query.heuristics

import org.springframework.stereotype.Component

/**
 * STUB - implementation deferred.
 *
 * Will trace the deployer EOA's funding source back N hops and detect Tornado
 * Cash / mixer touches, sanctioned-entity exposure, and known scam-wallet
 * cluster patterns. Chainalysis-style attribution work.
 */
@Component
class FundingFlowHeuristic : StubHeuristic(
    name = "funding_flow",
    plannedSignal = "Deployer funding-source trace: mixer touches, sanctioned-entity exposure, known scam-cluster overlap.",
)
