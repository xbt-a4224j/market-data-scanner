package io.github.xbta4224j.scanner.query.heuristics

import io.github.xbta4224j.scanner.query.HeuristicResult
import io.github.xbta4224j.scanner.query.RiskHeuristic
import io.github.xbta4224j.scanner.decoding.TokenContext

/**
 * Convenience base for the 5 deferred heuristics (and the 3 implemented-later
 * ones until #5/#6/#7 land). Returns score=0.5, confidence=0.0 so the
 * composite scorer ignores them, plus an evidence payload the dashboard can
 * render to explain what the heuristic WILL do once implemented.
 */
abstract class StubHeuristic(
    override val name: String,
    private val plannedSignal: String,
) : RiskHeuristic {

    override val version: String = "0.0.0-stub"

    override suspend fun evaluate(token: TokenContext): HeuristicResult = HeuristicResult(
        heuristicName = name,
        score = 0.5,
        confidence = 0.0,
        evidence = mapOf(
            "stubbed" to true,
            "plannedSignal" to plannedSignal,
        ),
        heuristicVersion = version,
        inputsHash = "n/a-stub",
        stubbed = true,
    )
}
