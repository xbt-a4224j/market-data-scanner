package io.github.xbta4224j.scanner.api

import io.github.xbta4224j.scanner.persistence.PoolDetection
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * UI-facing flattening of [PoolDetection] for SSE + JSON detail endpoints.
 * Strips the heuristic_results JSONB blob in favor of a per-heuristic
 * score + confidence summary the dashboard renders inline; the full
 * evidence map is fetched by the per-pool detail page on demand.
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
        )

        fun riskBand(score: Int): String = when {
            score >= 70 -> "high"
            score >= 40 -> "medium"
            else -> "low"
        }

        fun shortAddress(address: String): String =
            if (address.length >= 10) "${address.take(6)}..${address.takeLast(4)}" else address
    }
}
