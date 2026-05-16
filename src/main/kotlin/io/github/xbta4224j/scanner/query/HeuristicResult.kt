package io.github.xbta4224j.scanner.query

/**
 * Output of a single RiskHeuristic.
 *
 * `score` is the heuristic's risk estimate normalized to [0.0, 1.0] where 1.0
 * means "this token has every property of a scam" and 0.0 means "this token
 * looks completely clean". `confidence` is independent: how sure the heuristic
 * is in its score, also [0.0, 1.0]. A stub returns `confidence = 0.0` so it
 * contributes nothing to the composite even if `score` is set.
 *
 * `evidence` is the raw payload the dashboard renders to explain a score
 * (top-K holder breakdown, LP destination, deployer prior-deployment list,
 * etc.). It must be JSON-serializable since it persists to a JSONB column.
 *
 * `heuristicVersion` follows semver. When the heuristic logic changes,
 * re-scoring an old detection produces a result with a new version; old
 * scores remain reproducible by checking out the prior version.
 *
 * `inputsHash` is a stable hash over the canonical inputs to the heuristic
 * (typically token address + block hash + any external lookups). Lets the
 * audit trail prove "this score was computed from this exact input set".
 */
data class HeuristicResult(
    val heuristicName: String,
    val score: Double,
    val confidence: Double,
    val evidence: Map<String, Any>,
    val heuristicVersion: String,
    val inputsHash: String,
    val stubbed: Boolean = false,
) {
    init {
        require(score in 0.0..1.0) { "score $score outside [0.0, 1.0] for $heuristicName" }
        require(confidence in 0.0..1.0) { "confidence $confidence outside [0.0, 1.0] for $heuristicName" }
    }
}
