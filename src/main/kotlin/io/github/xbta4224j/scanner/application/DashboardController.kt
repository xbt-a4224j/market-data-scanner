package io.github.xbta4224j.scanner.application

import io.github.xbta4224j.scanner.indexing.PoolDetectionRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException

/**
 * Serves the Overview tab and the per-pool detail page.
 * Per-heuristic tabs are owned by [HeuristicTabsController] (Issue #10).
 */
@Controller
class DashboardController(
    private val poolDetections: PoolDetectionRepository,
    private val stats: StatsService,
    private val evidence: EvidenceSummaryService,
) {

    @GetMapping("/")
    fun overview(model: Model): String {
        model.addAttribute("stats", stats.snapshot())
        model.addAttribute("recent",
            poolDetections.findTop50ByOrderByDetectedAtDesc().map { PoolDetectionDto.from(it) })
        return "overview"
    }

    @GetMapping("/pool/{id}")
    fun detail(@PathVariable id: Long, model: Model): String {
        val pd = poolDetections.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "pool $id not found")
        }
        model.addAttribute("pool", PoolDetectionDto.from(pd))
        model.addAttribute("rawResults", pd.heuristicResults)
        model.addAttribute("evidence", evidence.summarize(pd))
        return "pool-detail"
    }
}
