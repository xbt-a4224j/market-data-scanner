package io.github.xbta4224j.scanner.chain

import java.time.OffsetDateTime

/**
 * Canonical record for a newly created Uniswap V3 pool, identifying the
 * "novel" token (the side of the pair that is not WETH or a known stablecoin)
 * along with all the chain-level provenance the heuristics need to reason
 * about it. Produced by [PoolCreatedDecoder] (Issue #3) and consumed by every
 * RiskHeuristic.
 *
 * All addresses are 0x-prefixed lowercase hex (42 chars). Hashes are 0x-prefixed
 * lowercase hex (66 chars).
 */
data class TokenContext(
    val tokenAddress: String,
    val deployerAddress: String,
    val poolAddress: String,
    val pairedWithAddress: String,
    val feeTier: Int,
    val blockNumber: Long,
    val blockHash: String,
    val blockTimestamp: OffsetDateTime,
    val txHash: String,
)
