package io.github.xbta4224j.scanner.indexing

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface IngestionRunRepository : JpaRepository<IngestionRun, Long> {

    fun findByStatus(status: String): List<IngestionRun>

    fun findTop20ByOrderByStartedAtDesc(): List<IngestionRun>

    @Query("SELECT r FROM IngestionRun r WHERE r.status = 'running' ORDER BY r.startedAt ASC")
    fun findRunningRuns(): List<IngestionRun>
}
