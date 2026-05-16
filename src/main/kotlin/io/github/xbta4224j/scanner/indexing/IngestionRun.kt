package io.github.xbta4224j.scanner.indexing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

/**
 * One row per ingestion execution. Live subscriptions and backfills both create
 * runs; the same row is updated as the run progresses (last_processed_block,
 * pools_detected) and finalized on completion (ended_at, status).
 *
 * Mode and status are open strings here, not enums, because the V1 CHECK
 * constraint already enforces valid values at the DB level and Postgres-VARCHAR
 * to JPA-enum mapping creates more friction than it removes.
 */
@Entity
@Table(name = "ingestion_runs")
class IngestionRun(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "mode", nullable = false, length = 20)
    var mode: String,

    @Column(name = "started_at", nullable = false)
    var startedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "ended_at")
    var endedAt: OffsetDateTime? = null,

    @Column(name = "from_block", nullable = false)
    var fromBlock: Long,

    @Column(name = "to_block")
    var toBlock: Long? = null,

    @Column(name = "last_processed_block")
    var lastProcessedBlock: Long? = null,

    @Column(name = "last_processed_block_hash", length = 66)
    var lastProcessedBlockHash: String? = null,

    @Column(name = "pools_detected", nullable = false)
    var poolsDetected: Int = 0,

    @Column(name = "status", nullable = false, length = 20)
    var status: String = IngestionRunStatus.RUNNING,

    @Column(name = "error_message", columnDefinition = "text")
    var errorMessage: String? = null,

    @Column(name = "created_by", nullable = false, length = 64)
    var createdBy: String = "system",
)

object IngestionRunMode {
    const val LIVE = "live"
    const val BACKFILL = "backfill"
}

object IngestionRunStatus {
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val REORGED = "reorged"
}
