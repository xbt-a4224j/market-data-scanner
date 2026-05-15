package io.github.xbta4224j.scanner.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.IdClass
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.io.Serializable
import java.time.OffsetDateTime

/**
 * Per-block ledger row keyed on the compound (block_number, block_hash) PK.
 *
 * Three load-bearing roles:
 *   1. Idempotency: re-processing the same (number, hash) is a no-op (PK conflict).
 *   2. Reorg detection: seeing block N with hash H' when (N, H) is already present
 *      means the chain reorged - mark the old row 'reorged', insert the new canonical.
 *   3. Resumability: backfill restarts skip already-processed blocks via a PK lookup.
 */
@Entity
@Table(name = "processed_blocks")
@IdClass(ProcessedBlockId::class)
class ProcessedBlock(

    @Id
    @Column(name = "block_number", nullable = false)
    var blockNumber: Long,

    @Id
    @Column(name = "block_hash", nullable = false, length = 66)
    var blockHash: String,

    @Column(name = "block_timestamp", nullable = false)
    var blockTimestamp: OffsetDateTime,

    @Column(name = "ingestion_run_id", nullable = false)
    var ingestionRunId: Long,

    @Column(name = "pool_count", nullable = false)
    var poolCount: Int = 0,

    @Column(name = "processed_at", nullable = false)
    var processedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "status", nullable = false, length = 20)
    var status: String = ProcessedBlockStatus.CANONICAL,
)

data class ProcessedBlockId(
    var blockNumber: Long = 0,
    var blockHash: String = "",
) : Serializable

object ProcessedBlockStatus {
    const val CANONICAL = "canonical"
    const val REORGED = "reorged"
}
