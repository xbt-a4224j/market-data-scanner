package io.github.xbta4224j.scanner.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ReviewDecisionRepository : JpaRepository<ReviewDecision, Long> {
    fun existsByPoolId(poolId: Long): Boolean
    fun findByPoolIdOrderByReviewedAtDesc(poolId: Long): List<ReviewDecision>
    fun countByLabel(label: String): Long
    fun findTop50ByOrderByReviewedAtDesc(): List<ReviewDecision>
}
