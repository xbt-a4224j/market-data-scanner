package io.github.xbta4224j.scanner.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

/**
 * Persisted record of a single Uniswap V3 pool-creation event after risk scoring.
 *
 * The (pool_address, block_hash) unique constraint in V1 makes reinsertion of the
 * same detected event idempotent - the pipeline can replay a block without
 * creating duplicate pool rows.
 *
 * heuristic_versions and heuristic_results are JSONB so the per-heuristic version
 * map and the full evidence payload are queryable by Postgres jsonb operators
 * without a normalized child table. flagged_signals is text[] for the same reason.
 */
@Entity
@Table(name = "pool_detections")
class PoolDetection(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "tx_hash", nullable = false, length = 66)
    var txHash: String,

    @Column(name = "pool_address", nullable = false, length = 42)
    var poolAddress: String,

    @Column(name = "token_address", nullable = false, length = 42)
    var tokenAddress: String,

    @Column(name = "deployer_address", nullable = false, length = 42)
    var deployerAddress: String,

    @Column(name = "paired_with", nullable = false, length = 42)
    var pairedWith: String,

    @Column(name = "fee_tier", nullable = false)
    var feeTier: Int,

    @Column(name = "block_number", nullable = false)
    var blockNumber: Long,

    @Column(name = "block_hash", nullable = false, length = 66)
    var blockHash: String,

    @Column(name = "block_timestamp", nullable = false)
    var blockTimestamp: OffsetDateTime,

    @Column(name = "detected_at", nullable = false)
    var detectedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "ingestion_run_id", nullable = false)
    var ingestionRunId: Long,

    @Column(name = "composite_score", nullable = false)
    var compositeScore: Int,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "heuristic_versions", nullable = false, columnDefinition = "jsonb")
    var heuristicVersions: Map<String, String> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "heuristic_results", nullable = false, columnDefinition = "jsonb")
    var heuristicResults: Map<String, Any> = emptyMap(),

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "flagged_signals", nullable = false, columnDefinition = "text[]")
    var flaggedSignals: List<String> = emptyList(),

    @Column(name = "status", nullable = false, length = 20)
    var status: String = PoolDetectionStatus.DETECTED,
)

object PoolDetectionStatus {
    const val DETECTED = "detected"
    const val REORGED = "reorged"
    const val REVIEWED = "reviewed"
}
