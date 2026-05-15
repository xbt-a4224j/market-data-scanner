package io.github.xbta4224j.scanner.analysis

import io.github.xbta4224j.scanner.chain.TokenContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Runs every registered RiskHeuristic in parallel against a TokenContext and
 * combines their results into a composite score in [0, 100].
 *
 * Combination rule: confidence-weighted average. Each heuristic's contribution
 * to the numerator is `weight * confidence * score`; the denominator sums
 * `weight * confidence` across all heuristics that returned a result. A
 * heuristic returning `confidence = 0` (stubs, or runtime-degraded paths)
 * contributes nothing - it doesn't drag the composite toward 0.5 the way a
 * naive weighted average would. When every heuristic is stubbed (total
 * confidence-weight is zero), the composite defaults to 50 (neutral).
 *
 * Weights come from `heuristics.weights.*` in application.yml. A heuristic with
 * no weight entry counts as weight 0 - i.e. it runs and produces evidence for
 * the dashboard but does not influence the composite. This is how new
 * heuristics ship dark before being wired in.
 */
@Component
class CompositeScorer(
    private val heuristics: List<RiskHeuristic>,
    private val config: HeuristicsConfig,
) {

    private val weights: Map<String, Double> get() = config.weights

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun score(token: TokenContext): CompositeScore = coroutineScope {
        val results = heuristics.map { h ->
            async {
                runCatching { h.evaluate(token) }
                    .onFailure { log.warn("heuristic ${h.name} threw, treating as confidence=0", it) }
                    .getOrElse { failed(h, it) }
            }
        }.map { it.await() }

        val numerator = results.sumOf { r ->
            val w = weights[r.heuristicName] ?: 0.0
            w * r.confidence * r.score
        }
        val denominator = results.sumOf { r ->
            val w = weights[r.heuristicName] ?: 0.0
            w * r.confidence
        }
        val composite = if (denominator > 0.0) {
            ((numerator / denominator) * 100.0).toInt().coerceIn(0, 100)
        } else {
            NEUTRAL_COMPOSITE
        }

        CompositeScore(
            composite = composite,
            results = results,
            totalConfidenceWeight = denominator,
        )
    }

    private fun failed(h: RiskHeuristic, t: Throwable) = HeuristicResult(
        heuristicName = h.name,
        score = 0.5,
        confidence = 0.0,
        evidence = mapOf("error" to (t.message ?: t.javaClass.simpleName)),
        heuristicVersion = h.version,
        inputsHash = "error",
        stubbed = false,
    )

    companion object {
        const val NEUTRAL_COMPOSITE = 50
    }
}

data class CompositeScore(
    /** [0, 100] where 100 means highest risk. */
    val composite: Int,
    val results: List<HeuristicResult>,
    /** Sum of (weight * confidence) across all heuristics. Zero when every heuristic is stubbed. */
    val totalConfidenceWeight: Double,
)
