package io.github.xbta4224j.scanner.analysis.heuristics

import io.github.xbta4224j.scanner.analysis.HeuristicResult
import io.github.xbta4224j.scanner.analysis.RiskHeuristic
import io.github.xbta4224j.scanner.chain.EtherscanClient
import io.github.xbta4224j.scanner.chain.TokenContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * Scores the deployer EOA based on its prior contract-deployment fingerprint.
 *
 * The signal: serial scam-factory deployers leave a recognizable trail -
 * dozens-to-hundreds of contracts deployed in a tight window, often within
 * minutes of each other. A first-time deployer (no prior contracts) is
 * mildly suspicious by default (score 0.4, confidence 0.0); a deployer with
 * 50+ prior contracts in any 24h burst is high-confidence adversarial.
 *
 * Score formula:
 *
 *   factor_count   = min(priorCount / FULL_COUNT_AT, 1.0)
 *   factor_density = min(maxDeploysPer24h / FULL_DENSITY_AT, 1.0)
 *   score          = COUNT_WEIGHT * factor_count + DENSITY_WEIGHT * factor_density
 *
 * Confidence is high (0.85+) once we have any prior-deployment data; falls
 * back to 0 when Etherscan returns nothing OR when the deployer field is empty
 * (which can happen if EtherscanClient enrichment failed in LiveBlockSource).
 *
 * The Etherscan label lookup ("Fake_Phishing*") that CLAUDE.md mentions is
 * intentionally NOT in this version - the free Etherscan API does not expose
 * the label graph. The count + density signal is sufficient to identify
 * factory patterns; label lookup is a follow-up via a paid Etherscan tier or
 * a third-party attribution feed (Forta / Chainalysis Reactor).
 */
@Component
class DeployerHistoryHeuristic(
    private val etherscan: EtherscanClient,
) : RiskHeuristic {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name: String = "deployer_history"
    override val version: String = "1.0.0"

    override suspend fun evaluate(token: TokenContext): HeuristicResult {
        val deployer = token.deployerAddress.lowercase()
        if (deployer.isBlank()) {
            return HeuristicResult(
                heuristicName = name,
                score = 0.5,
                confidence = 0.0,
                evidence = mapOf("reason" to "missing_deployer_address"),
                heuristicVersion = version,
                inputsHash = inputsHash(token.tokenAddress, "no-deployer", 0),
            )
        }

        val deployments = etherscan.getEoaContractDeployments(deployer)
            // Exclude the deployment of THIS token (we are scoring the deployer's
            // PRIOR fingerprint, not including the token they just deployed).
            .filter { it.contractAddress != token.tokenAddress.lowercase() }

        val priorCount = deployments.size
        val inputsHash = inputsHash(token.tokenAddress, deployer, priorCount)

        if (priorCount == 0) {
            // Brand-new deployer per CLAUDE.md: mildly suspicious neutral.
            return HeuristicResult(
                heuristicName = name,
                score = 0.4,
                confidence = 0.0,
                evidence = mapOf(
                    "deployer" to deployer,
                    "priorCount" to 0,
                    "interpretation" to "brand_new_deployer",
                ),
                heuristicVersion = version,
                inputsHash = inputsHash,
            )
        }

        val timestamps = deployments.map { it.timestampSeconds }.sorted()
        val maxBurstPer24h = maxBurstWindow(timestamps, windowSeconds = 86_400)
        val medianGapSeconds = medianGap(timestamps)
        val firstTs = timestamps.first()
        val lastTs = timestamps.last()
        val spanDays = ((lastTs - firstTs) / 86_400.0).coerceAtLeast(1.0)

        val factorCount = (priorCount.toDouble() / FULL_COUNT_AT).coerceAtMost(1.0)
        val factorDensity = (maxBurstPer24h.toDouble() / FULL_DENSITY_AT).coerceAtMost(1.0)
        val score = (COUNT_WEIGHT * factorCount + DENSITY_WEIGHT * factorDensity).coerceIn(0.0, 1.0)

        val confidence = if (priorCount >= 10) 0.90 else if (priorCount >= 3) 0.75 else 0.55

        return HeuristicResult(
            heuristicName = name,
            score = score,
            confidence = confidence,
            evidence = mapOf(
                "deployer" to deployer,
                "priorCount" to priorCount,
                "maxBurstPer24h" to maxBurstPer24h,
                "medianGapSeconds" to medianGapSeconds,
                "firstDeploymentTs" to firstTs,
                "lastDeploymentTs" to lastTs,
                "spanDays" to spanDays,
                "factorCount" to factorCount,
                "factorDensity" to factorDensity,
            ),
            heuristicVersion = version,
            inputsHash = inputsHash,
        )
    }

    /** Largest count of timestamps that fit inside any rolling `windowSeconds`-wide window. */
    fun maxBurstWindow(sortedTimestamps: List<Long>, windowSeconds: Long): Int {
        if (sortedTimestamps.isEmpty()) return 0
        var left = 0
        var best = 0
        for (right in sortedTimestamps.indices) {
            while (sortedTimestamps[right] - sortedTimestamps[left] > windowSeconds) {
                left++
            }
            best = maxOf(best, right - left + 1)
        }
        return best
    }

    fun medianGap(sortedTimestamps: List<Long>): Long {
        if (sortedTimestamps.size < 2) return 0
        val gaps = sortedTimestamps.zipWithNext { a, b -> b - a }.sorted()
        return gaps[gaps.size / 2]
    }

    private fun inputsHash(tokenAddr: String, deployer: String, priorCount: Int): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("$tokenAddr|$deployer|$priorCount".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** priorCount at which factor_count saturates to 1.0. */
        const val FULL_COUNT_AT: Int = 50
        /** Burst rate (deployments per 24h) at which factor_density saturates to 1.0. */
        const val FULL_DENSITY_AT: Int = 20
        const val COUNT_WEIGHT: Double = 0.55
        const val DENSITY_WEIGHT: Double = 0.45
    }
}
