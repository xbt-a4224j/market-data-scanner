package io.github.xbta4224j.scanner.query.heuristics

import io.github.xbta4224j.scanner.query.HeuristicResult
import io.github.xbta4224j.scanner.query.RiskHeuristic
import io.github.xbta4224j.scanner.ingestion.EtherscanClient
import io.github.xbta4224j.scanner.decoding.TokenContext
import io.github.xbta4224j.scanner.decoding.Transfer
import io.github.xbta4224j.scanner.decoding.TransferLogReader
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Walks Transfer events for the token between its creation block (resolved via
 * Etherscan) and the pool-creation block (`token.blockNumber`), reconstructs the
 * holder balance distribution, and scores how concentrated supply is.
 *
 * Score = `weight_top3 * top3% + weight_gini * gini`. A token with 95+% of supply
 * sitting in 1-3 wallets immediately after deployment is the textbook rug-pull
 * setup - that case scores >= 0.85.
 *
 * Confidence is high (~0.85) when we have transfer data; drops to 0 when the
 * eth_getLogs call returns nothing (very new token with no observable transfers
 * yet, or RPC failure).
 *
 * Burn addresses (0x0...0 and 0x...dead) are excluded from the holder set
 * before computing concentration - tokens commonly send a chunk to dead on
 * deploy, which would otherwise look like a "concentrated holder".
 */
@Component
class SupplyConcentrationHeuristic(
    private val reader: TransferLogReader,
    private val etherscan: EtherscanClient,
) : RiskHeuristic {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name: String = "supply_concentration"
    override val version: String = "1.0.0"

    override suspend fun evaluate(token: TokenContext): HeuristicResult {
        val creation = etherscan.getContractCreation(token.tokenAddress)
        val fromBlock = creation?.blockNumber ?: (token.blockNumber - 1000).coerceAtLeast(0)
        val toBlock = token.blockNumber

        val transfers = reader.fetchTransfers(token.tokenAddress, fromBlock, toBlock)
        val inputsHash = inputsHash(token.tokenAddress, fromBlock, toBlock, transfers.size)

        if (transfers.isEmpty()) {
            log.debug("no Transfer events for {} in {}..{} - returning low-confidence neutral",
                token.tokenAddress, fromBlock, toBlock)
            return HeuristicResult(
                heuristicName = name,
                score = 0.5,
                confidence = 0.0,
                evidence = mapOf(
                    "reason" to "no_transfers_found",
                    "fromBlock" to fromBlock,
                    "toBlock" to toBlock,
                ),
                heuristicVersion = version,
                inputsHash = inputsHash,
            )
        }

        val balances = computeBalances(transfers)
        val totalSupply = balances.values.fold(BigInteger.ZERO, BigInteger::add)
        if (totalSupply <= BigInteger.ZERO) {
            return HeuristicResult(
                heuristicName = name,
                score = 0.5,
                confidence = 0.0,
                evidence = mapOf(
                    "reason" to "zero_total_supply",
                    "transferCount" to transfers.size,
                ),
                heuristicVersion = version,
                inputsHash = inputsHash,
            )
        }

        val sorted = balances.values.sortedDescending()
        val top1 = ratio(sorted.take(1).fold(BigInteger.ZERO, BigInteger::add), totalSupply)
        val top3 = ratio(sorted.take(3).fold(BigInteger.ZERO, BigInteger::add), totalSupply)
        val top10 = ratio(sorted.take(10).fold(BigInteger.ZERO, BigInteger::add), totalSupply)
        val gini = giniCoefficient(sorted)

        val score = (TOP3_WEIGHT * top3 + GINI_WEIGHT * gini).coerceIn(0.0, 1.0)

        return HeuristicResult(
            heuristicName = name,
            score = score,
            confidence = 0.85,
            evidence = mapOf(
                "top1Pct" to top1,
                "top3Pct" to top3,
                "top10Pct" to top10,
                "gini" to gini,
                "totalHolders" to balances.size,
                "transferCount" to transfers.size,
                "fromBlock" to fromBlock,
                "toBlock" to toBlock,
            ),
            heuristicVersion = version,
            inputsHash = inputsHash,
        )
    }

    /** Replays Transfer events to reconstruct per-address balances. */
    fun computeBalances(transfers: List<Transfer>): Map<String, BigInteger> {
        val out = HashMap<String, BigInteger>()
        for (t in transfers) {
            val from = t.from.lowercase()
            val to = t.to.lowercase()
            // Mints (from=0x0): only credit `to`. Burns (to dead): only debit `from`.
            if (from != ZERO_ADDRESS) {
                out[from] = (out[from] ?: BigInteger.ZERO).subtract(t.value)
            }
            if (to !in BURN_SINKS) {
                out[to] = (out[to] ?: BigInteger.ZERO).add(t.value)
            }
        }
        // Drop dust + negatives (rounding artifacts from incomplete event windows).
        return out.filterValues { it > BigInteger.ZERO }
    }

    /**
     * Standard Gini coefficient on the holder balance distribution.
     * Input is descending-sorted balances; we reverse internally for the
     * ascending-sorted formula G = sum((2i - n - 1) * x_i) / (n * sum(x_i)).
     * Returns a value in [0, 1] where 0 is perfectly equal, 1 is one holder takes all.
     */
    fun giniCoefficient(sortedDescending: List<BigInteger>): Double {
        if (sortedDescending.size < 2) return 0.0
        val asc = sortedDescending.reversed()
        val n = asc.size
        val total = asc.fold(BigInteger.ZERO, BigInteger::add).toBigDecimal()
        if (total.signum() == 0) return 0.0
        var weighted = java.math.BigDecimal.ZERO
        asc.forEachIndexed { i, v ->
            val coeff = (2 * (i + 1) - n - 1).toBigDecimal()
            weighted = weighted.add(coeff.multiply(v.toBigDecimal()))
        }
        val gini = weighted.divide(n.toBigDecimal().multiply(total), 6, java.math.RoundingMode.HALF_UP)
        return gini.toDouble().coerceIn(0.0, 1.0)
    }

    private fun ratio(numerator: BigInteger, denominator: BigInteger): Double =
        numerator.toBigDecimal().divide(denominator.toBigDecimal(), 6, java.math.RoundingMode.HALF_UP).toDouble()

    private fun inputsHash(tokenAddr: String, from: Long, to: Long, transferCount: Int): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("$tokenAddr|$from|$to|$transferCount".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
        const val DEAD_ADDRESS = "0x000000000000000000000000000000000000dead"
        val BURN_SINKS: Set<String> = setOf(ZERO_ADDRESS, DEAD_ADDRESS)

        const val TOP3_WEIGHT: Double = 0.6
        const val GINI_WEIGHT: Double = 0.4
    }
}
