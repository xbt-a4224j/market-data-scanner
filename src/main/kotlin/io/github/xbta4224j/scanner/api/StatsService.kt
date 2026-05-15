package io.github.xbta4224j.scanner.api

import io.github.xbta4224j.scanner.analysis.HeuristicsConfig
import io.github.xbta4224j.scanner.analysis.RiskHeuristic
import io.github.xbta4224j.scanner.persistence.PoolDetectionRepository
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * Computes the small stats strip that anchors the Overview tab:
 *   - pools detected in the last hour
 *   - share of those pools with composite score >= high-risk threshold
 *   - highest composite score in the last hour
 *   - count of implemented (non-stub) heuristics
 */
@Service
class StatsService(
    private val poolDetections: PoolDetectionRepository,
    private val heuristics: List<RiskHeuristic>,
    private val config: HeuristicsConfig,
) {

    fun snapshot(): DashboardStats {
        val now = OffsetDateTime.now()
        val anHourAgo = now.minusHours(1)
        val highRisk = config.thresholds.highRisk
        val recentDetected = poolDetections.countDetectedSince(anHourAgo)
        val recentFlagged = poolDetections.countFlaggedSince(anHourAgo, highRisk)
        val pctFlagged = if (recentDetected > 0) (recentFlagged * 100.0 / recentDetected) else 0.0
        val topScore = poolDetections.findByCompositeScoreGreaterThanEqualAndStatusOrderByCompositeScoreDescDetectedAtDesc(
            scoreFloor = 0,
            status = "detected",
            pageable = org.springframework.data.domain.PageRequest.of(0, 1),
        ).firstOrNull()?.compositeScore ?: 0
        val implementedCount = heuristics.count { it.version.contains("stub").not() }

        return DashboardStats(
            poolsLastHour = recentDetected,
            pctFlaggedHighLastHour = pctFlagged,
            topScoreLastHour = topScore,
            heuristicsImplemented = implementedCount,
            heuristicsTotal = heuristics.size,
            highRiskThreshold = highRisk,
        )
    }
}

data class DashboardStats(
    val poolsLastHour: Long,
    val pctFlaggedHighLastHour: Double,
    val topScoreLastHour: Int,
    val heuristicsImplemented: Int,
    val heuristicsTotal: Int,
    val highRiskThreshold: Int,
)
