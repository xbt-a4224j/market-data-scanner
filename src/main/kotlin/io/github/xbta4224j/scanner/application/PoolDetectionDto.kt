package io.github.xbta4224j.scanner.application

import io.github.xbta4224j.scanner.indexing.PoolDetection
import java.time.format.DateTimeFormatter

/**
 * UI-facing flattening of [PoolDetection] for SSE + JSON detail endpoints.
 *
 * Display-friendly derived fields (shortened addresses, risk band, formatted
 * timestamp) are computed once in [from] and exposed as plain instance fields,
 * so Thymeleaf templates can render `${pool.shortTokenAddress}` without
 * having to invoke static helpers via SpEL `T(...)` (which has Kotlin
 * companion-object interop quirks and is brittle in general).
 */
data class PoolDetectionDto(
    val id: Long,
    val poolAddress: String,
    val tokenAddress: String,
    val deployerAddress: String,
    val pairedWithAddress: String,
    val feeTier: Int,
    val blockNumber: Long,
    val blockHash: String,
    val txHash: String,
    val detectedAt: String,
    val detectedAtTime: String,
    val compositeScore: Int,
    val riskBand: String,
    val flaggedSignals: List<String>,
    val heuristicVersions: Map<String, String>,
    // Display-only short forms of the addresses + tx hash. Computed at
    // construction so templates do not need to call any helpers.
    val shortTokenAddress: String,
    val shortDeployerAddress: String,
    val shortPairedWithAddress: String,
    val shortTxHash: String,
) {
    companion object {
        fun from(pd: PoolDetection): PoolDetectionDto = PoolDetectionDto(
            id = pd.id ?: -1,
            poolAddress = pd.poolAddress,
            tokenAddress = pd.tokenAddress,
            deployerAddress = pd.deployerAddress,
            pairedWithAddress = pd.pairedWith,
            feeTier = pd.feeTier,
            blockNumber = pd.blockNumber,
            blockHash = pd.blockHash,
            txHash = pd.txHash,
            detectedAt = pd.detectedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            detectedAtTime = pd.detectedAt.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            compositeScore = pd.compositeScore,
            riskBand = riskBand(pd.compositeScore),
            flaggedSignals = pd.flaggedSignals,
            heuristicVersions = pd.heuristicVersions,
            shortTokenAddress = shortAddress(pd.tokenAddress),
            shortDeployerAddress = shortAddress(pd.deployerAddress),
            shortPairedWithAddress = shortAddress(pd.pairedWith),
            shortTxHash = shortAddress(pd.txHash),
        )

        private fun riskBand(score: Int): String = when {
            score >= 70 -> "high"
            score >= 40 -> "medium"
            else -> "low"
        }

        private fun shortAddress(address: String): String =
            if (address.isBlank()) "—"
            else if (address.length >= 10) "${address.take(6)}..${address.takeLast(4)}"
            else address
    }
}
