package io.github.xbta4224j.scanner.indexing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "review_decisions")
class ReviewDecision(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "pool_id", nullable = false)
    var poolId: Long,

    @Column(name = "reviewer", nullable = false, length = 64)
    var reviewer: String,

    @Column(name = "label", nullable = false, length = 20)
    var label: String,

    @Column(name = "notes", columnDefinition = "text")
    var notes: String? = null,

    @Column(name = "reviewed_at", nullable = false)
    var reviewedAt: OffsetDateTime = OffsetDateTime.now(),
)

object ReviewLabel {
    const val LEGITIMATE = "legitimate"
    const val SCAM = "scam"
    const val UNCERTAIN = "uncertain"
    const val DEFERRED = "deferred"
    val ALL: Set<String> = setOf(LEGITIMATE, SCAM, UNCERTAIN, DEFERRED)
}
