package io.github.xbta4224j.scanner.observability

import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks

/**
 * Hot multicast stream of log records for the /admin/events live tab.
 * `InMemoryLogBuffer` pushes one event here per Logback append; the
 * EventsController exposes the stream as SSE.
 *
 * Multicast sink, 256-event back-pressure buffer, no replay - new
 * subscribers see entries from the moment they connect. The page renders
 * any backlog from the buffer's snapshot first, then connects to the
 * stream for tail-following.
 *
 * Mirrors the shape of [io.github.xbta4224j.scanner.api.PoolStreamPublisher]
 * deliberately: the canonical pipeline (ADR-006) has one Layer-6 SSE
 * pattern and both the pool feed and the event log are instances of it.
 */
@Component
class LogStreamPublisher {

    data class Event(
        val seq: Long,
        val timestamp: String,
        val level: String,
        val logger: String,
        val thread: String,
        val message: String,
        val throwable: String?,
    )

    private val sink: Sinks.Many<Event> =
        Sinks.many().multicast().onBackpressureBuffer(256, false)

    fun publish(event: Event) {
        // Drop on overflow; the buffer is the source of truth for history.
        sink.tryEmitNext(event)
    }

    fun flux(): Flux<Event> = sink.asFlux()
}
