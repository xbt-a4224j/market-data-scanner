package io.github.xbta4224j.scanner.application.admin

import io.github.xbta4224j.scanner.warehouse.CorpusIngestionService
import io.github.xbta4224j.scanner.indexing.CorpusEntryDao
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * /admin/corpus - status + manual refresh trigger for the legitimate-token
 * embedding corpus that powers the metadata-similarity heuristic.
 */
@Controller
class CorpusController(
    private val ingestion: CorpusIngestionService,
    private val dao: CorpusEntryDao,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/admin/corpus")
    fun page(model: Model): String {
        model.addAttribute("count", dao.count())
        model.addAttribute("bySource", dao.countBySource())
        return "admin/corpus"
    }

    @PostMapping("/admin/corpus/refresh")
    fun refresh(
        @RequestParam(defaultValue = "100") limit: Int,
        model: Model,
    ): String {
        val capped = limit.coerceIn(10, 250)
        log.info("admin triggered corpus refresh: top-{}", capped)
        val summary = ingestion.ingestTopN(capped)
        log.info("admin corpus refresh result: {}", summary)
        return "redirect:/admin/corpus"
    }
}
