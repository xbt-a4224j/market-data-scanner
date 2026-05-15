package io.github.xbta4224j.scanner.analysis.heuristics

import io.github.xbta4224j.scanner.analysis.HeuristicResult
import io.github.xbta4224j.scanner.analysis.RiskHeuristic
import io.github.xbta4224j.scanner.chain.LockContracts
import io.github.xbta4224j.scanner.chain.MintLogReader
import io.github.xbta4224j.scanner.chain.PriceOracle
import io.github.xbta4224j.scanner.chain.TokenContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * Classifies how the new pool's initial liquidity is held + values it in USD.
 *
 * Owner classification: deployer-held (high rug risk), burned, locked in a
 * known timelock contract (Unicrypt / Team.Finance / PinkLock / Mudra),
 * routed via Uniswap V3's NonfungiblePositionManager (cannot tell from Mint
 * alone - falls back to medium risk + low confidence), or unknown.
 *
 * Initial-liquidity USD valuation: priced via [PriceOracle] (stables 1:1,
 * WETH via CoinGecko spot). The "deployer-held LP + thin liquidity" pattern
 * gets a small additional risk bump on top of the classification score
 * when the USD value is below [THIN_LIQUIDITY_USD]. Above that threshold,
 * the deployer-held score is unchanged - thinness is the qualifier.
 */
@Component
class LpLockHeuristic(
    private val mintReader: MintLogReader,
    private val priceOracle: PriceOracle,
) : RiskHeuristic {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name: String = "lp_lock"
    override val version: String = "1.1.0"

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

        // USD valuation: the Mint event's amount0 / amount1 correspond to
        // (token0, token1) of the pool. Token0 < token1 by address-sort
        // convention. We try both sides; whichever priced side returns a
        // value gets used. (For WETH/USDC pools both sides will price; for
        // novel-token pools usually only the pricing-pair side does.)
        val pricedToken0 = priceOracle.valueLpSide(token.tokenAddress, firstMint.amount0)
        val pricedToken1 = priceOracle.valueLpSide(token.pairedWithAddress, firstMint.amount1)
        val pricedSide = pricedToken0 ?: pricedToken1
        val initialLiquidityUsd: BigDecimal? = pricedSide?.multiply(BigDecimal(2))  // both sides ~equal value at mint

        val (baseScore, baseConfidence) = classification.scoreAndConfidence
        val (score, thinLiquidityFlag) = applyThinLiquidityBump(baseScore, classification, initialLiquidityUsd)

        val evidence: MutableMap<String, Any> = mutableMapOf(
            "lpClassification" to classification.label,
            "lpOwner" to firstMint.owner,
            "mintSender" to firstMint.sender,
            "amount0" to firstMint.amount0.toString(),
            "amount1" to firstMint.amount1.toString(),
            "mintBlock" to firstMint.blockNumber,
            "mintTxHash" to firstMint.txHash,
        )
        initialLiquidityUsd?.let { evidence["initialLiquidityUsd"] = it.toPlainString() }
        if (thinLiquidityFlag) evidence["thinLiquidityFlag"] = true

        return HeuristicResult(
            heuristicName = name,
            score = score,
            confidence = baseConfidence,
            evidence = evidence,
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

    /**
     * Deployer-held LP with sub-$THIN_LIQUIDITY_USD initial value gets bumped
     * to 0.96 (vs 0.92 baseline). All other classifications are unchanged.
     */
    private fun applyThinLiquidityBump(
        baseScore: Double,
        classification: LpClassification,
        usd: BigDecimal?,
    ): Pair<Double, Boolean> {
        if (classification != LpClassification.DEPLOYER_HELD) return baseScore to false
        if (usd == null) return baseScore to false
        return if (usd < THIN_LIQUIDITY_USD) (0.96 to true) else (baseScore to false)
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
        val THIN_LIQUIDITY_USD: BigDecimal = BigDecimal("2000")  // textbook rug seed liquidity
    }
}
