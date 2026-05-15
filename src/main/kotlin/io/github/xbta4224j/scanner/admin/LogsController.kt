package io.github.xbta4224j.scanner.admin

import io.github.xbta4224j.scanner.observability.InMemoryLogBuffer
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * /admin/logs - lets the reviewer skim the last N records in the in-memory
 * log buffer with an optional level filter (ERROR / WARN / INFO / DEBUG).
 * Defaults to WARN+ which is what oncall would actually want to see.
 */
@Controller
class LogsController(
    private val buffer: InMemoryLogBuffer,
) {

    @GetMapping("/admin/logs")
    fun logs(
        @RequestParam(defaultValue = "WARN") minLevel: String,
        @RequestParam(defaultValue = "200") limit: Int,
        model: Model,
    ): String {
        val effectiveLimit = limit.coerceIn(10, 1000)
        model.addAttribute("entries", buffer.recent(effectiveLimit, minLevel))
        model.addAttribute("summary", buffer.summary())
        model.addAttribute("minLevel", minLevel.uppercase())
        model.addAttribute("limit", effectiveLimit)
        return "admin/logs"
    }
}
