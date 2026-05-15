package io.github.xbta4224j.scanner.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ProcessedBlockRepository : JpaRepository<ProcessedBlock, ProcessedBlockId> {

    /** Idempotency check: has this exact (number, hash) been processed already? */
    fun existsByBlockNumberAndBlockHash(blockNumber: Long, blockHash: String): Boolean

    /** Reorg detection: any canonical row at this height with a different hash? */
    @Query(
        """
        SELECT b FROM ProcessedBlock b
        WHERE b.blockNumber = :number
          AND b.blockHash <> :hash
          AND b.status = 'canonical'
        """
    )
    fun findCanonicalAtHeightWithDifferentHash(
        @Param("number") number: Long,
        @Param("hash") hash: String,
    ): List<ProcessedBlock>

    /**
     * Mark a previously-canonical block as reorged when a competing hash wins.
     * `clearAutomatically` evicts cached entities so subsequent finds reload
     * the fresh state instead of returning the stale canonical row.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        """
        UPDATE ProcessedBlock b
           SET b.status = 'reorged'
         WHERE b.blockNumber = :number
           AND b.blockHash = :hash
           AND b.status = 'canonical'
        """
    )
    fun markReorged(@Param("number") number: Long, @Param("hash") hash: String): Int
}
