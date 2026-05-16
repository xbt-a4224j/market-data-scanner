package io.github.xbta4224j.scanner.api

import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import reactor.core.publisher.Flux
import java.time.Duration

/**
 * SSE endpoint at `/stream` carrying live pool detections. Consumed by the
 * dashboard's Overview tab via htmx-sse. Heartbeat every 15s so reverse
 * proxies do not drop the connection during quiet periods.
 *
 * Each event's payload is the rendered `<tr>` HTML for the row - rendered
 * server-side from the same `fragments/feed-row :: row(pool)` Thymeleaf
 * fragment that the static initial render uses. htmx-sse drops the HTML
 * straight into the tbody as `afterbegin`, so SSE rows are visually
 * indistinguishable from page-load rows. (The previous implementation
 * pushed the JSON DTO; htmx then inserted the JSON text as-is, which
 * is what produced the wall-of-JSON look in the Live Feed.)
 */
@RestController
class PoolStreamController(
    private val publisher: PoolStreamPublisher,
    private val templateEngine: SpringTemplateEngine,
) {

    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(): Flux<ServerSentEvent<String>> {
        val data: Flux<ServerSentEvent<String>> = publisher.flux().map { dto ->
            ServerSentEvent.builder<String>(renderRow(dto))
                .event("pool")
                .id(dto.id.toString())
                .build()
        }
        val heartbeat: Flux<ServerSentEvent<String>> = Flux.interval(Duration.ofSeconds(15)).map {
            ServerSentEvent.builder<String>("ping").event("heartbeat").build()
        }
        return Flux.merge(data, heartbeat)
    }

    /**
     * Render the shared feed-row fragment to a single-line `<tr>` HTML
     * string. Newlines collapsed because SSE `data:` framing is line-
     * delimited - htmx tolerates multi-line `data:` chunks but a single
     * line keeps the wire format predictable.
     */
    private fun renderRow(dto: PoolDetectionDto): String {
        val ctx = Context().apply {
            setVariable("pool", dto)
            // Adds the `fresh` CSS class so the row gets the gradient-flash
            // animation. Static initial render passes nothing, so existing
            // rows render plain.
            setVariable("rowClass", "fresh")
        }
        return templateEngine.process("fragments/feed-row", setOf("row"), ctx)
            .replace("\n", "")
            .trim()
    }
}
