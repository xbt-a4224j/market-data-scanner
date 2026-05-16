package io.github.xbta4224j.scanner.chain

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * USD valuation helper. Used by LpLockHeuristic to quote initial-liquidity
 * value, which combined with the LP destination classification produces the
 * "deployer-held + thin liquidity = rug pattern" signal.
 *
 * Stablecoins (USDC / USDT / DAI / FDUSD / etc.) are valued at 1:1 with USD
 * and decimals-adjusted. WETH is valued via CoinGecko spot price, refreshed
 * every 5 minutes (Caffeine cache TTL). Other paired tokens fall through to
 * a null quote - the heuristic surfaces "value unavailable" rather than
 * guessing.
 */
@Component
class PriceOracle(
    @Value("\${corpus.coingecko.base-url:https://api.coingecko.com/api/v3}") private val coingeckoBase: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rest = RestClient.builder().build()
    private val mapper = ObjectMapper()

    /**
     * Returns the USD value of `rawAmount` units of the token at `tokenAddress`
     * (lowercased), or null if the token is not in the supported price map.
     * `rawAmount` is in the token's native integer units (decimal-adjusted
     * here from the token's known decimals).
     */
    fun valueInUsd(tokenAddress: String, rawAmount: BigInteger): BigDecimal? {
        val addr = tokenAddress.lowercase()
        val (decimals, pricePerUnit) = SUPPORTED[addr] ?: run {
            // For non-stables paired with the WETH price feed
            if (addr == WETH_ADDRESS) {
                val ethPrice = ethPriceUsd() ?: return null
                return scale(rawAmount, 18, ethPrice)
            }
            return null
        }
        return scale(rawAmount, decimals, pricePerUnit)
    }

    /**
     * Convenience: compute USD value of one side of a Uniswap V3 Mint (amount0
     * or amount1). Caller passes the side's token address and the raw amount.
     */
    fun valueLpSide(tokenAddress: String, amount: BigInteger): BigDecimal? =
        valueInUsd(tokenAddress, amount)

    @Cacheable("coingecko-spot", key = "'eth-spot-usd'")
    fun ethPriceUsd(): BigDecimal? {
        return runCatching {
            val body = rest.get()
                .uri("$coingeckoBase/simple/price?ids=ethereum&vs_currencies=usd")
                .retrieve()
                .body<String>() ?: return null
            val node = mapper.readTree(body)
            val priceNode = node["ethereum"]?.get("usd") ?: return null
            BigDecimal(priceNode.asText())
        }.onFailure { log.warn("eth price fetch failed", it) }
            .getOrNull()
    }

    private fun scale(rawAmount: BigInteger, decimals: Int, pricePerUnit: BigDecimal): BigDecimal {
        // value = rawAmount / 10^decimals * pricePerUnit
        val divisor = BigDecimal.TEN.pow(decimals)
        return BigDecimal(rawAmount).divide(divisor, 6, RoundingMode.HALF_UP)
            .multiply(pricePerUnit)
            .setScale(2, RoundingMode.HALF_UP)
    }

    companion object {
        const val WETH_ADDRESS = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2"

        // Lowercased mainnet address -> (decimals, USD price per whole unit).
        // Stablecoins anchored at 1.0; ETH-priced tokens fall through to the
        // CoinGecko spot fetch above.
        private val SUPPORTED: Map<String, Pair<Int, BigDecimal>> = mapOf(
            "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48" to (6 to BigDecimal.ONE),   // USDC
            "0xdac17f958d2ee523a2206206994597c13d831ec7" to (6 to BigDecimal.ONE),   // USDT
            "0x6b175474e89094c44da98b954eedeac495271d0f" to (18 to BigDecimal.ONE),  // DAI
            "0x4c9edd5852cd905f086c759e8383e09bff1e68b3" to (18 to BigDecimal.ONE),  // USDe
            "0x853d955acef822db058eb8505911ed77f175b99e" to (18 to BigDecimal.ONE),  // FRAX
        )
    }
}
