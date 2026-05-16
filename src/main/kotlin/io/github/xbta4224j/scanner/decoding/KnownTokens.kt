package io.github.xbta4224j.scanner.decoding

/**
 * Lower-cased mainnet address -> human symbol for the handful of tokens that
 * are the canonical "pair" side of new V3 pools. Used by:
 *
 *  - [PoolCreatedDecoder] to pick which side of token0/token1 is "novel"
 *    (the side NOT in this map).
 *  - The Overview tab's charts to render English captions like
 *    "most often paired with WETH (32 of 50)" instead of a 0x address.
 *
 * Not a full token registry - just the routing-class tokens that the
 * UniV3 factory pairs new launches against. Adding a symbol here is one
 * line; it does not affect heuristic behavior.
 */
object KnownTokens {

    val SYMBOLS: Map<String, String> = mapOf(
        "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2" to "WETH",
        "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48" to "USDC",
        "0xdac17f958d2ee523a2206206994597c13d831ec7" to "USDT",
        "0x6b175474e89094c44da98b954eedeac495271d0f" to "DAI",
        "0x2260fac5e5542a773aa44fbcfedf7c193bc2c599" to "WBTC",
        "0x5f98805a4e8be255a32880fdec7f6728c6568ba0" to "LUSD",
        "0x853d955acef822db058eb8505911ed77f175b99e" to "FRAX",
    )

    /** Returns the symbol if known, otherwise a shortened 0x..xxxx form. */
    fun symbolOrShort(address: String): String {
        val lower = address.lowercase()
        SYMBOLS[lower]?.let { return it }
        return if (address.length >= 10) "${address.take(6)}..${address.takeLast(4)}" else address
    }

    val WELL_KNOWN_ADDRESSES: Set<String> get() = SYMBOLS.keys
}
