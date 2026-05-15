package io.github.xbta4224j.scanner.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

@Repository
interface PoolDetectionRepository : JpaRepository<PoolDetection, Long> {

    fun findTop50ByOrderByDetectedAtDesc(): List<PoolDetection>

    fun findByStatusOrderByDetectedAtDesc(status: String, pageable: Pageable): List<PoolDetection>

    fun findByCompositeScoreGreaterThanEqualAndStatusOrderByCompositeScoreDescDetectedAtDesc(
        scoreFloor: Int,
        status: String,
        pageable: Pageable,
    ): List<PoolDetection>

    fun findByDeployerAddress(deployerAddress: String): List<PoolDetection>

    fun findByBlockHash(blockHash: String): List<PoolDetection>

    @Query(
        """
        SELECT COUNT(p) FROM PoolDetection p
        WHERE p.detectedAt >= :since AND p.status = 'detected'
        """
    )
    fun countDetectedSince(@Param("since") since: OffsetDateTime): Long

    @Query(
        """
        SELECT COUNT(p) FROM PoolDetection p
        WHERE p.detectedAt >= :since
          AND p.status = 'detected'
          AND p.compositeScore >= :scoreFloor
        """
    )
    fun countFlaggedSince(
        @Param("since") since: OffsetDateTime,
        @Param("scoreFloor") scoreFloor: Int,
    ): Long
}
