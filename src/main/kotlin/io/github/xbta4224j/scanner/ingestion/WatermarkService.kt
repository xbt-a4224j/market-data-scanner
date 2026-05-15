package io.github.xbta4224j.scanner.ingestion

import io.github.xbta4224j.scanner.persistence.IngestionState
import io.github.xbta4224j.scanner.persistence.IngestionStateRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Tracks the contiguous-processed block range. A single row (id=1) carries the
 * earliest and latest block numbers we have processed. Live ingestion extends
 * forward; backfill extends backward. Reads are unsynchronized; writes use a
 * Postgres-level row lock via SELECT ... FOR UPDATE inside @Transactional to
 * prevent races between live and backfill writers.
 */
@Service
class WatermarkService(
    private val states: IngestionStateRepository,
) {

    fun current(): IngestionState =
        states.findById(SINGLETON_ID).orElseGet {
            // V1 migration seeds row 1; this fallback is for tests that
            // accidentally truncate the table.
            states.save(IngestionState(id = SINGLETON_ID))
        }

    fun getLatestProcessed(): Long? = current().latestProcessedBlock

    fun getEarliestProcessed(): Long? = current().earliestProcessedBlock

    fun getLatestProcessedHash(): String? = current().latestProcessedBlockHash

    @Transactional
    fun extendForward(blockNumber: Long, blockHash: String) {
        val state = current()
        val currentLatest = state.latestProcessedBlock
        if (currentLatest == null || blockNumber > currentLatest) {
            state.latestProcessedBlock = blockNumber
            state.latestProcessedBlockHash = blockHash
            if (state.earliestProcessedBlock == null) {
                state.earliestProcessedBlock = blockNumber
            }
            state.lastUpdated = OffsetDateTime.now()
            states.save(state)
        }
    }

    @Transactional
    fun extendBackward(blockNumber: Long) {
        val state = current()
        val currentEarliest = state.earliestProcessedBlock
        if (currentEarliest == null || blockNumber < currentEarliest) {
            state.earliestProcessedBlock = blockNumber
            if (state.latestProcessedBlock == null) {
                state.latestProcessedBlock = blockNumber
            }
            state.lastUpdated = OffsetDateTime.now()
            states.save(state)
        }
    }

    companion object {
        const val SINGLETON_ID = 1
    }
}
