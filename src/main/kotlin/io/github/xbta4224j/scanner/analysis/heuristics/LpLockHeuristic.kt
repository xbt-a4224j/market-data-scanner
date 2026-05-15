package io.github.xbta4224j.scanner.analysis.heuristics

import org.springframework.stereotype.Component

/**
 * STUB - real implementation lands in Issue #6.
 *
 * Will track the LP token's destination after the pool's first Mint and
 * classify it as deployer-held (high rug risk), burned (locked, low risk),
 * locked in a known timelock contract (Unicrypt / Team Finance / Pinklock /
 * Mudra Locker), or unknown. Combined with initial liquidity in USD.
 */
@Component
class LpLockHeuristic : StubHeuristic(
    name = "lp_lock",
    plannedSignal = "LP-token destination + initial liquidity USD (deployer-held LP and thin liquidity = rug pattern).",
)
