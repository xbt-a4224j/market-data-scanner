package io.github.xbta4224j.scanner.api

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks

/**
 * Hot multicast stream of pool detections. The IngestionPipeline pushes a
 * [PoolDetectionDto] here every time it persists a detection;
 * [PoolStreamController] subscribes to expose the stream as SSE on /stream.
 *
 * Uses a multicast sink with a 256-event back-pressure buffer. New
 * subscribers DO NOT replay history - they only see events from the moment
 * they connect.
 */
@Component
class PoolStreamPublisher {

    private val log = LoggerFactory.getLogger(javaClass)
    private val sink: Sinks.Many<PoolDetectionDto> =
        Sinks.many().multicast().onBackpressureBuffer(256, false)

    fun publish(dto: PoolDetectionDto) {
        when (val result = sink.tryEmitNext(dto)) {
            Sinks.EmitResult.OK -> {}
            else -> log.warn("pool stream emit dropped: {}", result)
        }
    }

    fun flux(): Flux<PoolDetectionDto> = sink.asFlux()
}
