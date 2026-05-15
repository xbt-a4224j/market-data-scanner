package io.github.xbta4224j.scanner.analysis.heuristics

import io.github.xbta4224j.scanner.analysis.HeuristicResult
import io.github.xbta4224j.scanner.analysis.RiskHeuristic
import io.github.xbta4224j.scanner.chain.LockContracts
import io.github.xbta4224j.scanner.chain.MintLogReader
import io.github.xbta4224j.scanner.chain.TokenContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * Classifies how the new pool's initial liquidity is held: by the deployer
 * (high rug risk), in a known LP lock contract (low risk), burned (lowest
 * risk), routed via Uniswap V3's NonfungiblePositionManager (cannot tell from
 * Mint alone - falls back to medium risk + low confidence), or unknown.
 *
 * The heuristic reads the FIRST Mint event for the pool between its creation
 * block and a small look-ahead window. The Mint event's `owner` topic
 * identifies who the V3 position is credited to.
 *
 * Initial-liquidity USD valuation is intentionally NOT computed here - that
 * needs a price oracle (CoinGecko or sqrtPriceX96 derivation). Tracked as a
 * follow-up; for now the heuristic surfaces amount0/amount1 raw in evidence
 * for the dashboard to render.
 */
@Component
class LpLockHeuristic(
    private val mintReader: MintLogReader,
) : RiskHeuristic {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name: String = "lp_lock"
    override val version: String = "1.0.0"

    override suspend fun evaluate(token: TokenContext): HeuristicResult {
        val fromBlock = token.blockNumber
        val toBlock = token.blockNumber + LOOKAHEAD_BLOCKS

        val firstMint = mintReader.fetchFirstMint(token.poolAddress, fromBlock, toBlock)
        val inputsHash = inputsHash(token.poolAddress, fromBlock, toBlock, firstMint?.txHash ?: "none")

        if (firstMint == null) {
            log.debug("no Mint event for pool {} in {}..{}", token.poolAddress, fromBlock, toBlock)
            return HeuristicResult(
                heuristicName = name,
                score = 0.5,
                confidence = 0.0,
                evidence = mapOf(
                    "reason" to "no_mint_in_window",
                    "fromBlock" to fromBlock,
                    "toBlock" to toBlock,
                ),
                heuristicVersion = version,
                inputsHash = inputsHash,
            )
        }

        val classification = classify(firstMint.owner.lowercase(), token.deployerAddress.lowercase())
        val (score, confidence) = classification.scoreAndConfidence

        return HeuristicResult(
            heuristicName = name,
            score = score,
            confidence = confidence,
            evidence = mapOf(
                "lpClassification" to classification.label,
                "lpOwner" to firstMint.owner,
                "mintSender" to firstMint.sender,
                "amount0" to firstMint.amount0.toString(),
                "amount1" to firstMint.amount1.toString(),
                "mintBlock" to firstMint.blockNumber,
                "mintTxHash" to firstMint.txHash,
            ),
            heuristicVersion = version,
            inputsHash = inputsHash,
        )
    }

    private fun classify(owner: String, deployer: String): LpClassification = when {
        owner in LockContracts.BURN_SINKS -> LpClassification.BURNED
        owner in LockContracts.ALL_LOCKS -> LpClassification.LOCKED
        owner == deployer && deployer.isNotBlank() -> LpClassification.DEPLOYER_HELD
        owner == LockContracts.UNISWAP_V3_NPM -> LpClassification.VIA_NPM
        else -> LpClassification.UNKNOWN
    }

    private enum class LpClassification(val label: String, val scoreAndConfidence: Pair<Double, Double>) {
        DEPLOYER_HELD("deployer_held", 0.92 to 0.85),
        BURNED("burned", 0.05 to 0.85),
        LOCKED("locked", 0.10 to 0.85),
        VIA_NPM("via_npm", 0.50 to 0.30),
        UNKNOWN("unknown", 0.55 to 0.50),
    }

    private fun inputsHash(pool: String, from: Long, to: Long, txHash: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("$pool|$from|$to|$txHash".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val LOOKAHEAD_BLOCKS: Long = 50  // ~10min window for the first Mint to land
    }
}
