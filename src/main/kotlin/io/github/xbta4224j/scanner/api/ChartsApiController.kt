package io.github.xbta4224j.scanner.api

import io.github.xbta4224j.scanner.persistence.PoolDetectionRepository
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

/**
 * JSON endpoints feeding the dashboard's charts. Kept separate from the page
 * controllers so the templates can refresh with fetch() without re-rendering
 * the whole layout.
 */
@RestController
class ChartsApiController(
    private val poolDetections: PoolDetectionRepository,
) {

    /**
     * Distribution of composite scores across recent detections, bucketed in
     * 10-point bands. Drives the histogram on the Overview tab.
     */
    @GetMapping("/api/charts/score-histogram", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun scoreHistogram(): Map<String, Any> {
        val recent = poolDetections.findTop50ByOrderByDetectedAtDesc()
        val buckets = IntArray(10)
        recent.forEach { pd ->
            val bucket = (pd.compositeScore / 10).coerceIn(0, 9)
            buckets[bucket]++
        }
        return mapOf(
            "labels" to (0..9).map { "${it * 10}-${it * 10 + 9}" },
            "data"   to buckets.toList(),
            "total"  to recent.size,
        )
    }

    /**
     * Detection counts in the last hour, bucketed per minute. Drives the
     * sparkline on the "pools last hour" stat card.
     */
    @GetMapping("/api/charts/detections-sparkline", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun sparkline(): Map<String, Any> {
        val recent = poolDetections.findTop50ByOrderByDetectedAtDesc()
        val now = OffsetDateTime.now()
        val anHourAgo = now.minusHours(1)
        val perMinute = IntArray(60)
        recent.filter { it.detectedAt > anHourAgo }
            .forEach { pd ->
                val minutesAgo = java.time.Duration.between(pd.detectedAt, now).toMinutes().toInt()
                if (minutesAgo in 0..59) perMinute[59 - minutesAgo]++
            }
        return mapOf(
            "labels" to (0..59).map { "${it - 59}m" },
            "data"   to perMinute.toList(),
        )
    }

    /**
     * Risk-band totals from the recent feed. Drives a small donut on the
     * Overview that visualizes how the population breaks down today.
     */
    @GetMapping("/api/charts/risk-bands", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun riskBands(): Map<String, Any> {
        val recent = poolDetections.findTop50ByOrderByDetectedAtDesc()
        val low    = recent.count { it.compositeScore < 40 }
        val medium = recent.count { it.compositeScore in 40..69 }
        val high   = recent.count { it.compositeScore >= 70 }
        return mapOf(
            "labels" to listOf("Low (<40)", "Medium (40-69)", "High (70+)"),
            "data"   to listOf(low, medium, high),
            "colors" to listOf("#047857", "#b45309", "#b91c1c"),
        )
    }
}
