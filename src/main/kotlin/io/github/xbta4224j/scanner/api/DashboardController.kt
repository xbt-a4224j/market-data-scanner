package io.github.xbta4224j.scanner.api

import io.github.xbta4224j.scanner.persistence.PoolDetectionRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * Serves the Overview tab and the per-pool detail page.
 * Per-heuristic tabs are owned by [HeuristicTabsController] (Issue #10).
 */
@Controller
class DashboardController(
    private val poolDetections: PoolDetectionRepository,
    private val stats: StatsService,
) {

    @GetMapping("/")
    fun overview(model: Model): String {
        model.addAttribute("stats", stats.snapshot())
        model.addAttribute("recent",
            poolDetections.findTop50ByOrderByDetectedAtDesc().map { PoolDetectionDto.from(it) })
        return "overview"
    }

    @GetMapping("/pool/{id}")
    fun detail(@org.springframework.web.bind.annotation.PathVariable id: Long, model: Model): String {
        val pd = poolDetections.findById(id).orElseThrow {
            org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "pool $id not found"
            )
        }
        model.addAttribute("pool", PoolDetectionDto.from(pd))
        model.addAttribute("rawResults", pd.heuristicResults)
        return "pool-detail"
    }
}
