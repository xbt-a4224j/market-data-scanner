package io.github.xbta4224j.scanner.api

import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import java.time.Duration

/**
 * SSE endpoint at `/stream` carrying live pool detections. Consumed by the
 * dashboard's Overview tab via htmx-sse. Heartbeat every 15s so reverse
 * proxies do not drop the connection during quiet periods.
 */
@RestController
class PoolStreamController(
    private val publisher: PoolStreamPublisher,
) {

    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(): Flux<ServerSentEvent<Any>> {
        val data: Flux<ServerSentEvent<Any>> = publisher.flux().map { dto ->
            ServerSentEvent.builder<Any>(dto)
                .event("pool")
                .id(dto.id.toString())
                .build()
        }
        val heartbeat: Flux<ServerSentEvent<Any>> = Flux.interval(Duration.ofSeconds(15)).map {
            ServerSentEvent.builder<Any>("ping").event("heartbeat").build()
        }
        return Flux.merge(data, heartbeat)
    }
}
