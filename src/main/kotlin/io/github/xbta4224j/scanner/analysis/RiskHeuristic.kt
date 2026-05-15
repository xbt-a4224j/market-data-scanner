package io.github.xbta4224j.scanner.analysis

import io.github.xbta4224j.scanner.chain.TokenContext

/**
 * The contract every risk heuristic implements. Implementations are stateless
 * Spring beans; CompositeScorer discovers them via type and runs them in
 * parallel against the same TokenContext.
 *
 * Each heuristic owns its own RPC / Etherscan / embedding calls. Failure to
 * fetch external data should produce a low-confidence result, never throw -
 * the composite scorer must keep producing scores even when one heuristic
 * is degraded.
 */
interface RiskHeuristic {
    /** Stable identifier used for weights, metrics labels, and JSONB keys. */
    val name: String

    /** Semver. Bump on any behavior change so historical scores stay reproducible. */
    val version: String

    /** Run the heuristic. Should not throw; degraded paths return low confidence. */
    suspend fun evaluate(token: TokenContext): HeuristicResult
}
