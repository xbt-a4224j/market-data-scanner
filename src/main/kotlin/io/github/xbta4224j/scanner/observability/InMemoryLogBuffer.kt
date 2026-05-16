package io.github.xbta4224j.scanner.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded in-memory ring buffer of the most recent application log records.
 * Backs the /admin/logs view so reviewers can spot-check error/warn activity
 * without SSHing onto the box or wiring a full log-aggregator.
 *
 * Holds at most [CAPACITY] records. Older records are evicted in FIFO order.
 *
 * Self-attaches to the Logback root logger on construction so it captures
 * every log line emitted from anywhere in the JVM (Spring, Hibernate,
 * web3j, our own packages).
 */
@Component
class InMemoryLogBuffer(
    private val streamPublisher: LogStreamPublisher,
) {

    data class Entry(
        val seq: Long,
        val timestamp: OffsetDateTime,
        val level: String,
        val logger: String,
        val thread: String,
        val message: String,
        val mdc: Map<String, String>,
        val throwable: String?,
    ) {
        // Last segment of a dotted logger name (e.g. "...LiveBlockSource").
        // Computed here so templates can render `${e.loggerShort}` without
        // calling Thymeleaf's #strings utility, whose `substringAfterLast`
        // is not available in every Spring Boot 3.x line.
        val loggerShort: String get() = logger.substringAfterLast('.')
    }

    private val buffer = ConcurrentLinkedDeque<Entry>()
    private val seqGen = AtomicLong(0)

    init {
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(eventObject: ILoggingEvent) {
                val entry = Entry(
                    seq = seqGen.incrementAndGet(),
                    timestamp = OffsetDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(eventObject.timeStamp),
                        ZoneOffset.UTC,
                    ),
                    level = eventObject.level.toString(),
                    logger = eventObject.loggerName,
                    thread = eventObject.threadName,
                    message = eventObject.formattedMessage,
                    mdc = HashMap(eventObject.mdcPropertyMap.orEmpty()),
                    throwable = eventObject.throwableProxy
                        ?.let { "${it.className}: ${it.message ?: ""}" },
                )
                buffer.addLast(entry)
                while (buffer.size > CAPACITY) buffer.pollFirst()

                // Tee to the live SSE stream for /admin/events. Wrap in a
                // try/catch so a faulty subscriber can never break logging
                // itself - the buffer remains authoritative on its own.
                runCatching {
                    streamPublisher.publish(LogStreamPublisher.Event(
                        seq = entry.seq,
                        timestamp = entry.timestamp.toString(),
                        level = entry.level,
                        logger = entry.logger,
                        thread = entry.thread,
                        message = entry.message,
                        throwable = entry.throwable,
                    ))
                }
            }
        }
        appender.context = rootLogger.loggerContext
        appender.start()
        rootLogger.addAppender(appender)
    }

    /**
     * Per-minute ERROR + WARN counts over the last [minutes] minutes, oldest
     * bucket first. Backs the sparkline on /admin/events. Empty minutes are
     * returned as zeros so the chart's x-axis is continuous.
     */
    fun errorRateSparkline(minutes: Int = 5): List<MinuteBucket> {
        val nowEpochMin = System.currentTimeMillis() / 60_000
        val buckets = LongArray(minutes) { 0 }
        val warns = LongArray(minutes) { 0 }
        for (e in buffer) {
            val mins = e.timestamp.toEpochSecond() / 60
            val ago = (nowEpochMin - mins).toInt()
            if (ago in 0 until minutes) {
                val idx = (minutes - 1) - ago
                when (e.level) {
                    "ERROR" -> buckets[idx]++
                    "WARN"  -> warns[idx]++
                }
            }
        }
        return (0 until minutes).map { i ->
            MinuteBucket(
                minutesAgo = (minutes - 1) - i,
                errors = buckets[i],
                warns = warns[i],
            )
        }
    }

    data class MinuteBucket(val minutesAgo: Int, val errors: Long, val warns: Long)

    /** Returns up to [limit] most-recent entries that match [minLevel] or higher, newest first. */
    fun recent(limit: Int = 200, minLevel: String? = null): List<Entry> {
        val floor = minLevel?.let { Level.toLevel(it.uppercase(), Level.TRACE) } ?: Level.TRACE
        return buffer.toList()
            .asReversed()
            .filter { Level.toLevel(it.level, Level.TRACE).toInt() >= floor.toInt() }
            .take(limit)
    }

    fun summary(): Summary {
        val all = buffer.toList()
        return Summary(
            total = all.size,
            errors = all.count { it.level == "ERROR" },
            warns = all.count { it.level == "WARN" },
            info = all.count { it.level == "INFO" },
            debug = all.count { it.level == "DEBUG" || it.level == "TRACE" },
        )
    }

    data class Summary(val total: Int, val errors: Int, val warns: Int, val info: Int, val debug: Int)

    companion object {
        const val CAPACITY = 5000
    }
}
