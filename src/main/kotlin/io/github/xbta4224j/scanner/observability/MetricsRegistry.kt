package io.github.xbta4224j.scanner.observability

import io.github.xbta4224j.scanner.ingestion.WatermarkService
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for the project's custom Micrometer metrics. Other beans
 * call its mark / record helpers; the underlying instruments stay scoped to
 * this class. Exposed via /actuator/prometheus.
 */
@Component
class MetricsRegistry(
    private val registry: MeterRegistry,
    watermark: WatermarkService,
) {

    val poolsDetectedTotal: Counter = Counter.builder("pools_detected_total")
        .description("Total Uniswap V3 pool detections persisted")
        .register(registry)

    private val heuristicFiredCounters = ConcurrentHashMap<String, Counter>()
    private val heuristicLatencyTimers = ConcurrentHashMap<String, Timer>()
    private val errorCounters = ConcurrentHashMap<String, Counter>()

    init {
        registry.gauge(
            "latest_processed_block",
            watermark,
        ) { it.getLatestProcessed()?.toDouble() ?: Double.NaN }

        registry.gauge(
            "earliest_processed_block",
            watermark,
        ) { it.getEarliestProcessed()?.toDouble() ?: Double.NaN }
    }

    fun markHeuristicFired(name: String, outcome: HeuristicOutcome) {
        heuristicFiredCounters.computeIfAbsent("$name|${outcome.name}") {
            Counter.builder("heuristic_fired_total")
                .description("Number of heuristic evaluations, tagged by name + outcome (flagged/clean/degraded)")
                .tags(Tags.of("heuristic", name, "outcome", outcome.name.lowercase()))
                .register(registry)
        }.increment()
    }

    fun timeHeuristic(name: String): Timer = heuristicLatencyTimers.computeIfAbsent(name) {
        Timer.builder("heuristic_latency_seconds")
            .description("Per-heuristic evaluation latency")
            .tags(Tags.of("heuristic", name))
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry)
    }

    fun markError(type: String) {
        errorCounters.computeIfAbsent(type) {
            Counter.builder("errors_total")
                .description("Total errors, tagged by type (rpc / etherscan / db / pipeline / scoring)")
                .tags(Tags.of("type", type))
                .register(registry)
        }.increment()
    }

    enum class HeuristicOutcome { FLAGGED, CLEAN, DEGRADED }
}

/**
 * Wall-clock seconds since the last block was added to the watermark.
 * Surfaces a "we are alive and consuming" signal for ops dashboards.
 */
@Component
class SecondsSinceLastBlockGauge(
    registry: MeterRegistry,
    private val watermark: WatermarkService,
) {
    private var lastSeenAt: Instant? = null
    private var lastSeenBlock: Long? = null

    init {
        registry.gauge("seconds_since_last_block", this) { gauge ->
            val current = gauge.watermark.getLatestProcessed()
            if (current != null && current != gauge.lastSeenBlock) {
                gauge.lastSeenAt = Instant.now()
                gauge.lastSeenBlock = current
            }
            val seenAt = gauge.lastSeenAt ?: return@gauge Double.NaN
            (Instant.now().epochSecond - seenAt.epochSecond).toDouble()
        }
    }
}
