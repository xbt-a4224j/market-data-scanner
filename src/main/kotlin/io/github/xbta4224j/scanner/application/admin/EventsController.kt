package io.github.xbta4224j.scanner.application.admin

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.xbta4224j.scanner.observability.InMemoryLogBuffer
import io.github.xbta4224j.scanner.observability.LogStreamPublisher
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import reactor.core.publisher.Flux
import java.time.Duration

/**
 * Live event-log tab. Renders /admin/events as a tail-following SSE view of
 * the in-memory log buffer; complementary to /admin/logs which is a
 * snapshot+filter view for review.
 *
 * The page subscribes to /admin/events/stream via htmx-sse. The publisher
 * sends one event per Logback append; the page swaps each new entry into
 * the top of the table with the standard `fresh-fade` animation that the
 * Live Feed tab uses.
 */
@Controller
class EventsController(
    private val buffer: InMemoryLogBuffer,
    private val streamPublisher: LogStreamPublisher,
    private val objectMapper: ObjectMapper,
) {

    @GetMapping("/admin/events")
    fun page(
        @RequestParam(defaultValue = "INFO") minLevel: String,
        model: Model,
    ): String {
        val recent = buffer.recent(60, minLevel)
        val sparkline = buffer.errorRateSparkline(5)
        model.addAttribute("entries", recent)
        model.addAttribute("summary", buffer.summary())
        model.addAttribute("sparkline", sparkline)
        model.addAttribute("minLevel", minLevel.uppercase())
        return "admin/events"
    }

    /**
     * SSE endpoint. Emits one `event` per log record, plus a `heartbeat`
     * every 15s so reverse proxies don't drop the connection. Each event's
     * data is the rendered HTML row for direct htmx swap.
     */
    @GetMapping("/admin/events/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @ResponseBody
    fun stream(): Flux<ServerSentEvent<String>> {
        val data: Flux<ServerSentEvent<String>> = streamPublisher.flux().map { ev ->
            ServerSentEvent.builder<String>(renderRow(ev))
                .event("event")
                .id(ev.seq.toString())
                .build()
        }
        val heartbeat: Flux<ServerSentEvent<String>> = Flux.interval(Duration.ofSeconds(15)).map {
            ServerSentEvent.builder<String>("ping").event("heartbeat").build()
        }
        return Flux.merge(data, heartbeat)
    }

    /**
     * Emit a single `<tr>` matching the static-render template so
     * server-sent rows visually match server-rendered rows. Kept
     * server-side instead of client-side because the JSON DTO would
     * otherwise need a separate JS template - more code paths to keep
     * in sync.
     */
    private fun renderRow(ev: LogStreamPublisher.Event): String {
        val levelClass = when (ev.level) {
            "ERROR" -> "level-error"
            "WARN" -> "level-warn"
            "INFO" -> "level-info"
            "DEBUG" -> "level-debug"
            else -> "level-trace"
        }
        // Time portion only (HH:mm:ss); the wall-clock date adds noise in a
        // tail-follower view.
        val time = ev.timestamp.substringAfter('T').take(8)
        val loggerShort = ev.logger.substringAfterLast('.')
        val message = escape(ev.message)
        val throwable = ev.throwable?.let { "<div class=\"event-throwable\">${escape(it)}</div>" } ?: ""
        return """<tr class="event-row fresh" data-level="${ev.level}">
            <td class="event-time">${time}</td>
            <td><span class="event-chip ${levelClass}">${ev.level}</span></td>
            <td class="event-logger" title="${escape(ev.logger)}">${escape(loggerShort)}</td>
            <td class="event-message">${message}${throwable}</td>
        </tr>""".trimIndent().replace("\n", "")
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
