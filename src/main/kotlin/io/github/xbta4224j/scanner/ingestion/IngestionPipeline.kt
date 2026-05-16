package io.github.xbta4224j.scanner.ingestion

import io.github.xbta4224j.scanner.query.CompositeScorer
import io.github.xbta4224j.scanner.application.PoolDetectionDto
import io.github.xbta4224j.scanner.application.PoolStreamPublisher
import io.github.xbta4224j.scanner.observability.MetricsRegistry
import io.github.xbta4224j.scanner.ingestion.BlockSource
import io.github.xbta4224j.scanner.decoding.TokenContext
import io.github.xbta4224j.scanner.indexing.IngestionRun
import io.github.xbta4224j.scanner.indexing.IngestionRunMode
import io.github.xbta4224j.scanner.indexing.IngestionRunRepository
import io.github.xbta4224j.scanner.indexing.IngestionRunStatus
import io.github.xbta4224j.scanner.indexing.PoolDetection
import io.github.xbta4224j.scanner.indexing.PoolDetectionRepository
import io.github.xbta4224j.scanner.indexing.PoolDetectionStatus
import io.github.xbta4224j.scanner.indexing.ProcessedBlock
import io.github.xbta4224j.scanner.indexing.ProcessedBlockRepository
import io.github.xbta4224j.scanner.indexing.ProcessedBlockStatus
import kotlinx.coroutines.flow.collect
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime

/**
 * The shared ingestion pipeline. Consumes any [BlockSource] (live WSS
 * subscription or historical backfill iterator), runs the composite scorer
 * against each emitted [TokenContext], handles reorgs, and persists results
 * to `pool_detections` + `processed_blocks` + updates the run / watermark.
 *
 * Same code path for live and backfill - that is the central architectural
 * claim: "the pipeline does not know it is running historically; same code,
 * different BlockSource implementation".
 */
@Service
class IngestionPipeline(
    private val scorer: CompositeScorer,
    private val poolDetections: PoolDetectionRepository,
    private val processedBlocks: ProcessedBlockRepository,
    private val ingestionRuns: IngestionRunRepository,
    private val watermark: WatermarkService,
    private val streamPublisher: PoolStreamPublisher,
    private val metrics: MetricsRegistry,
    txManager: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)

    /**
     * Drive the source to completion. Returns the IngestionRun row in its
     * final state (COMPLETED / FAILED). Used by both live (long-running
     * coroutine) and backfill (bounded iteration) modes.
     */
    suspend fun run(source: BlockSource, fromBlock: Long, toBlock: Long? = null): IngestionRun {
        var run = ingestionRuns.save(IngestionRun(
            mode = source.mode.name.lowercase(),
            fromBlock = fromBlock,
            toBlock = toBlock,
            status = IngestionRunStatus.RUNNING,
            createdBy = if (source.mode == BlockSource.Mode.LIVE) "live" else "backfill",
        ))
        log.info("ingestion run {} starting in mode={} fromBlock={} toBlock={}",
            run.id, run.mode, fromBlock, toBlock)

        runCatching {
            source.subscribe().collect { ctx -> processOne(ctx, run) }
            run.status = IngestionRunStatus.COMPLETED
        }.onFailure { err ->
            log.error("ingestion run {} failed: {}", run.id, err.message, err)
            run.status = IngestionRunStatus.FAILED
            run.errorMessage = err.message?.take(2000)
        }

        run.endedAt = OffsetDateTime.now()
        run = ingestionRuns.save(run)
        log.info("ingestion run {} ended status={} pools_detected={}",
            run.id, run.status, run.poolsDetected)
        return run
    }

    /**
     * Process a single TokenContext: handle reorg, idempotency check, score,
     * persist. Public so tests can drive synthetic contexts through this
     * method directly without spinning up a real BlockSource. Persistence
     * runs through Spring Data's per-query transactions; we do not wrap the
     * whole method in @Transactional because Spring's transactional proxy
     * does not work cleanly with suspend functions.
     */
    suspend fun processOne(ctx: TokenContext, run: IngestionRun) {
        // 1. Reorg detection (synchronous, transactional - JPQL UPDATEs need a tx)
        tx.executeWithoutResult {
            val competing = processedBlocks.findCanonicalAtHeightWithDifferentHash(
                number = ctx.blockNumber,
                hash = ctx.blockHash,
            )
            for (c in competing) {
                log.warn("reorg detected at height {}: marking old hash {} as reorged in favor of {}",
                    ctx.blockNumber, c.blockHash, ctx.blockHash)
                processedBlocks.markReorged(c.blockNumber, c.blockHash)
                poolDetections.findByBlockHash(c.blockHash).forEach { stale ->
                    stale.status = PoolDetectionStatus.REORGED
                    poolDetections.save(stale)
                }
            }
        }

        // 2. Idempotency: same (block, hash, pool) seen before -> skip
        val existing = poolDetections.findByBlockHash(ctx.blockHash).firstOrNull {
            it.poolAddress.equals(ctx.poolAddress, ignoreCase = true)
        }
        if (existing != null) {
            log.debug("pool {} at block {} already detected (id={}) - idempotent skip",
                ctx.poolAddress, ctx.blockNumber, existing.id)
            return
        }

        // 3. Score (suspending - heuristics are coroutine-based)
        val composite = scorer.score(ctx)

        // 4. Persist - synchronous, transactional
        val flagged = composite.results
            .filter { it.confidence >= 0.5 && it.score >= 0.7 }
            .map { it.heuristicName }
        val versions = composite.results.associate { it.heuristicName to it.heuristicVersion }
        val results = composite.results.associate { r ->
            r.heuristicName to mapOf(
                "score" to r.score,
                "confidence" to r.confidence,
                "evidence" to r.evidence,
                "stubbed" to r.stubbed,
            )
        }
        val saved = tx.execute {
            // processed_blocks save - PK conflict = no-op (idempotency contract)
            runCatching {
                processedBlocks.save(ProcessedBlock(
                    blockNumber = ctx.blockNumber,
                    blockHash = ctx.blockHash,
                    blockTimestamp = ctx.blockTimestamp,
                    ingestionRunId = run.id ?: error("run.id null"),
                    poolCount = 1,
                    status = ProcessedBlockStatus.CANONICAL,
                ))
            }.onFailure { log.debug("processed_blocks insert raced (idempotent): {}", it.message) }

            val saved = poolDetections.save(PoolDetection(
                txHash = ctx.txHash,
                poolAddress = ctx.poolAddress,
                tokenAddress = ctx.tokenAddress,
                deployerAddress = ctx.deployerAddress,
                pairedWith = ctx.pairedWithAddress,
                feeTier = ctx.feeTier,
                blockNumber = ctx.blockNumber,
                blockHash = ctx.blockHash,
                blockTimestamp = ctx.blockTimestamp,
                ingestionRunId = run.id!!,
                compositeScore = composite.composite,
                heuristicVersions = versions,
                heuristicResults = results,
                flaggedSignals = flagged,
            ))

            run.poolsDetected += 1
            run.lastProcessedBlock = ctx.blockNumber
            run.lastProcessedBlockHash = ctx.blockHash
            ingestionRuns.save(run)
            saved
        }

        // Metrics + SSE
        if (saved != null) {
            metrics.poolsDetectedTotal.increment()
            composite.results.forEach { r ->
                val outcome = when {
                    r.confidence == 0.0 -> MetricsRegistry.HeuristicOutcome.DEGRADED
                    r.score >= 0.7      -> MetricsRegistry.HeuristicOutcome.FLAGGED
                    else                -> MetricsRegistry.HeuristicOutcome.CLEAN
                }
                metrics.markHeuristicFired(r.heuristicName, outcome)
            }
            streamPublisher.publish(PoolDetectionDto.from(saved))
        }

        // 5. Watermark extends - has its own @Transactional inside
        when (run.mode) {
            IngestionRunMode.LIVE -> watermark.extendForward(ctx.blockNumber, ctx.blockHash)
            IngestionRunMode.BACKFILL -> watermark.extendBackward(ctx.blockNumber)
        }

        log.info("persisted pool detection id={} pool={} composite={} flagged={}",
            saved?.id, ctx.poolAddress, composite.composite, flagged)
    }
}
