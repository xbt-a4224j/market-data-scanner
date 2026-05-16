package io.github.xbta4224j.scanner.indexing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

/**
 * Singleton watermark row tracking the contiguous-processed block range across all
 * ingestion runs. Live ingestion extends `latestProcessedBlock` forward; backfill
 * extends `earliestProcessedBlock` backward. The V1 migration seeds row id=1.
 */
@Entity
@Table(name = "ingestion_state")
class IngestionState(

    @Id
    @Column(name = "id")
    var id: Int = 1,

    @Column(name = "earliest_processed_block")
    var earliestProcessedBlock: Long? = null,

    @Column(name = "latest_processed_block")
    var latestProcessedBlock: Long? = null,

    @Column(name = "latest_processed_block_hash", length = 66)
    var latestProcessedBlockHash: String? = null,

    @Column(name = "last_updated", nullable = false)
    var lastUpdated: OffsetDateTime = OffsetDateTime.now(),
)
