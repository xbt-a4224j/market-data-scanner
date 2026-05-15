package io.github.xbta4224j.scanner.persistence

import io.github.xbta4224j.scanner.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@SpringBootTest
@Transactional
class ProcessedBlockRepositoryTest @Autowired constructor(
    private val processedBlocks: ProcessedBlockRepository,
    private val ingestionRuns: IngestionRunRepository,
) : PostgresIntegrationTest() {

    private var runId: Long = 0

    @BeforeEach
    fun seedRun() {
        val run = ingestionRuns.save(
            IngestionRun(
                mode = IngestionRunMode.LIVE,
                fromBlock = 22_500_000,
                status = IngestionRunStatus.RUNNING,
            )
        )
        runId = run.id!!
    }

    @Test
    fun `existsByBlockNumberAndBlockHash returns true once persisted - idempotency check`() {
        val block = sampleBlock(22_500_001, "0xaaa")
        processedBlocks.save(block)

        assertThat(
            processedBlocks.existsByBlockNumberAndBlockHash(22_500_001, "0xaaa")
        ).isTrue()
        assertThat(
            processedBlocks.existsByBlockNumberAndBlockHash(22_500_001, "0xbbb")
        ).isFalse()
    }

    @Test
    fun `findCanonicalAtHeightWithDifferentHash detects competing canonical hash at same height`() {
        processedBlocks.save(sampleBlock(22_500_002, "0xaaa"))
        processedBlocks.save(sampleBlock(22_500_002, "0xbbb"))

        val competing = processedBlocks.findCanonicalAtHeightWithDifferentHash(
            number = 22_500_002,
            hash = "0xccc",  // hypothetical incoming hash
        )

        assertThat(competing.map { it.blockHash }).containsExactlyInAnyOrder("0xaaa", "0xbbb")
    }

    @Test
    fun `markReorged flips a canonical row to reorged status`() {
        processedBlocks.save(sampleBlock(22_500_003, "0xddd"))

        val updated = processedBlocks.markReorged(22_500_003, "0xddd")
        processedBlocks.flush()

        assertThat(updated).isEqualTo(1)
        val reloaded = processedBlocks.findById(ProcessedBlockId(22_500_003, "0xddd")).orElseThrow()
        assertThat(reloaded.status).isEqualTo(ProcessedBlockStatus.REORGED)
    }

    private fun sampleBlock(number: Long, hash: String) = ProcessedBlock(
        blockNumber = number,
        blockHash = hash,
        blockTimestamp = OffsetDateTime.parse("2026-05-14T00:00:00Z"),
        ingestionRunId = runId,
        poolCount = 0,
        status = ProcessedBlockStatus.CANONICAL,
    )
}
