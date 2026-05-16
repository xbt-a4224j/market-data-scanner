package io.github.xbta4224j.scanner.application

import io.github.xbta4224j.scanner.indexing.PoolDetectionRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException

/**
 * Per-heuristic tab. One template, one route; whether the page renders rich
 * evidence or a "STUBBED" placeholder is driven by [HeuristicMetadata].
 */
@Controller
class HeuristicTabsController(
    private val poolDetections: PoolDetectionRepository,
) {

    @GetMapping("/heuristics/{name}")
    fun tab(@PathVariable name: String, model: Model): String {
        val meta = HeuristicMetadata.BY_KEY[name]
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "unknown heuristic '$name'")

        val recent = poolDetections.findTop50ByOrderByDetectedAtDesc()
            .map { pd ->
                val perHeuristic = (pd.heuristicResults[name] as? Map<*, *>) ?: emptyMap<String, Any>()
                HeuristicTabRow(
                    pool = PoolDetectionDto.from(pd),
                    heuristicScore = (perHeuristic["score"] as? Number)?.toDouble() ?: 0.0,
                    heuristicConfidence = (perHeuristic["confidence"] as? Number)?.toDouble() ?: 0.0,
                    heuristicStubbed = perHeuristic["stubbed"] as? Boolean ?: false,
                    evidence = (perHeuristic["evidence"] as? Map<*, *>)
                        ?.entries?.associate { (k, v) -> k.toString() to (v ?: "") }
                        ?: emptyMap(),
                )
            }

        model.addAttribute("meta", meta)
        model.addAttribute("rows", recent)
        return "heuristic-tab"
    }
}

data class HeuristicTabRow(
    val pool: PoolDetectionDto,
    val heuristicScore: Double,
    val heuristicConfidence: Double,
    val heuristicStubbed: Boolean,
    val evidence: Map<String, Any>,
)
