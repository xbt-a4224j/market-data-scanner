package io.github.xbta4224j.scanner.ingestion

import io.github.xbta4224j.scanner.chain.BlockSource
import io.github.xbta4224j.scanner.chain.TokenContext
import io.github.xbta4224j.scanner.persistence.IngestionRunRepository
import io.github.xbta4224j.scanner.persistence.PoolDetectionRepository
import io.github.xbta4224j.scanner.persistence.PoolDetectionStatus
import io.github.xbta4224j.scanner.persistence.ProcessedBlockId
import io.github.xbta4224j.scanner.persistence.ProcessedBlockRepository
import io.github.xbta4224j.scanner.persistence.ProcessedBlockStatus
import io.github.xbta4224j.scanner.support.PostgresIntegrationTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.OffsetDateTime

@SpringBootTest
class IngestionPipelineTest @Autowired constructor(
    private val pipeline: IngestionPipeline,
    private val poolDetections: PoolDetectionRepository,
    private val processedBlocks: ProcessedBlockRepository,
    private val ingestionRuns: IngestionRunRepository,
    private val watermark: WatermarkService,
) : PostgresIntegrationTest() {

    @BeforeEach
    fun reset() = clearAllSharedState()

    @AfterEach
    fun cleanupAfterTest() = clearAllSharedState()

    /**
     * Pipeline tests write through real commits (no @Transactional rollback),
     * so we must clear all shared state ourselves. Done both before AND after
     * each test so other test classes that read this database see a clean
     * slate after this class runs.
     */
    private fun clearAllSharedState() {
        poolDetections.deleteAll()
        processedBlocks.deleteAll()
        ingestionRuns.deleteAll()
        watermark.reset()
    }

    @Test
    fun `live happy path - two pool detections persist with composite scores`(): Unit = runBlocking {
        val source = StubBlockSource(BlockSource.Mode.LIVE, listOf(
            sampleContext(blockNumber = 22_500_001, blockHash = "0xaa", poolAddress = "0xpool1"),
            sampleContext(blockNumber = 22_500_002, blockHash = "0xbb", poolAddress = "0xpool2"),
        ))

        val run = pipeline.run(source, fromBlock = 22_500_000)

        assertThat(run.status).isEqualTo("completed")
        assertThat(run.poolsDetected).isEqualTo(2)

        val pools = poolDetections.findTop50ByOrderByDetectedAtDesc()
        assertThat(pools).hasSize(2)
        assertThat(pools.map { it.poolAddress.lowercase() }).containsExactlyInAnyOrder("0xpool1", "0xpool2")
        assertThat(pools.first().compositeScore).isBetween(0, 100)

        // ProcessedBlock + watermark advanced
        assertThat(processedBlocks.existsByBlockNumberAndBlockHash(22_500_002, "0xbb")).isTrue()
        assertThat(watermark.getLatestProcessed()).isEqualTo(22_500_002)
    }

    @Test
    fun `idempotency - same pool emitted twice persists only once`(): Unit = runBlocking {
        val ctx = sampleContext(blockNumber = 22_500_010, blockHash = "0xcc", poolAddress = "0xidempotent")
        val source = StubBlockSource(BlockSource.Mode.LIVE, listOf(ctx, ctx))

        pipeline.run(source, fromBlock = 22_500_000)

        val pools = poolDetections.findTop50ByOrderByDetectedAtDesc()
            .filter { it.poolAddress.lowercase() == "0xidempotent" }
        assertThat(pools).hasSize(1)
    }

    @Test
    fun `reorg - same height with new hash marks the old block + its detections as reorged`(): Unit = runBlocking {
        // First run: block N at hash H
        val firstSource = StubBlockSource(BlockSource.Mode.LIVE, listOf(
            sampleContext(blockNumber = 22_500_020, blockHash = "0xoldhash", poolAddress = "0xpoolA"),
        ))
        pipeline.run(firstSource, fromBlock = 22_500_000)

        // Second run: same height N, NEW hash H' (the reorg)
        val secondSource = StubBlockSource(BlockSource.Mode.LIVE, listOf(
            sampleContext(blockNumber = 22_500_020, blockHash = "0xnewhash", poolAddress = "0xpoolB"),
        ))
        pipeline.run(secondSource, fromBlock = 22_500_000)

        // Old processed_blocks row marked reorged; new one canonical
        val old = processedBlocks.findById(ProcessedBlockId(22_500_020, "0xoldhash")).orElseThrow()
        val new = processedBlocks.findById(ProcessedBlockId(22_500_020, "0xnewhash")).orElseThrow()
        assertThat(old.status).isEqualTo(ProcessedBlockStatus.REORGED)
        assertThat(new.status).isEqualTo(ProcessedBlockStatus.CANONICAL)

        // Old pool detection marked reorged; new one detected
        val all = poolDetections.findAll().toList()
        assertThat(all.single { it.blockHash == "0xoldhash" }.status).isEqualTo(PoolDetectionStatus.REORGED)
        assertThat(all.single { it.blockHash == "0xnewhash" }.status).isEqualTo(PoolDetectionStatus.DETECTED)
    }

    @Test
    fun `failed run records FAILED status and error message`(): Unit = runBlocking {
        val source = ThrowingBlockSource(BlockSource.Mode.BACKFILL)

        val run = pipeline.run(source, fromBlock = 22_500_000, toBlock = 22_500_100)

        assertThat(run.status).isEqualTo("failed")
        assertThat(run.errorMessage).contains("synthetic backfill error")
    }

    private fun sampleContext(blockNumber: Long, blockHash: String, poolAddress: String) = TokenContext(
        tokenAddress = "0x" + blockHash.removePrefix("0x").padStart(40, '0'),
        deployerAddress = "0x" + "1".repeat(40),
        poolAddress = poolAddress,
        pairedWithAddress = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",
        feeTier = 3000,
        blockNumber = blockNumber,
        blockHash = blockHash,
        blockTimestamp = OffsetDateTime.parse("2026-05-14T00:00:00Z"),
        txHash = "0x" + blockHash.removePrefix("0x").padStart(64, '0'),
    )

    private class StubBlockSource(
        override val mode: BlockSource.Mode,
        private val items: List<TokenContext>,
    ) : BlockSource {
        override fun subscribe(): Flow<TokenContext> = flow { items.forEach { emit(it) } }
    }

    private class ThrowingBlockSource(override val mode: BlockSource.Mode) : BlockSource {
        override fun subscribe(): Flow<TokenContext> = flow { error("synthetic backfill error") }
    }
}
