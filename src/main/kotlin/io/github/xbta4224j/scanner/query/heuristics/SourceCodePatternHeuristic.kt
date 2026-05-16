package io.github.xbta4224j.scanner.query.heuristics

import org.springframework.stereotype.Component

/**
 * STUB - implementation deferred.
 *
 * When the contract is verified on Etherscan, will AST-parse the Solidity
 * source for known scam patterns: hidden fees activated after N blocks,
 * whitelist transfer modifiers, owner-only mint without timelock, suspicious
 * `rescue` functions, hidden `setMaxTx` toggles.
 */
@Component
class SourceCodePatternHeuristic : StubHeuristic(
    name = "source_code_pattern",
    plannedSignal = "Solidity AST analysis for known rug patterns (hidden fees, whitelist transfers, owner-mint without timelock).",
)
