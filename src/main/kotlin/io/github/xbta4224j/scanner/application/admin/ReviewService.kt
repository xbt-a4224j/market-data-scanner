package io.github.xbta4224j.scanner.application.admin

import io.github.xbta4224j.scanner.indexing.DeployerReputation
import io.github.xbta4224j.scanner.indexing.DeployerReputationRepository
import io.github.xbta4224j.scanner.indexing.PoolDetection
import io.github.xbta4224j.scanner.indexing.PoolDetectionRepository
import io.github.xbta4224j.scanner.indexing.PoolDetectionStatus
import io.github.xbta4224j.scanner.indexing.ReviewDecision
import io.github.xbta4224j.scanner.indexing.ReviewDecisionRepository
import io.github.xbta4224j.scanner.indexing.ReviewLabel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Human-in-the-loop labeling workflow.
 *
 * - LEGITIMATE: today, just records the decision and bumps
 *   `legitimate_associations` on the deployer's reputation row. The
 *   "embed token metadata into the pgvector corpus" path is no-op'd until
 *   Issue #19 ships proper embedding-provider wiring (Anthropic does not
 *   offer an embeddings API, so the corpus pipeline needs a separate
 *   provider - tracked there).
 * - SCAM: records the decision and bumps `scam_confirmations` on the
 *   deployer's reputation row. The DeployerHistoryHeuristic will read
 *   these in a future revision to add a "confirmed-scam-by-reviewer"
 *   bonus score.
 */
@Service
class ReviewService(
    private val poolDetections: PoolDetectionRepository,
    private val reviewDecisions: ReviewDecisionRepository,
    private val deployerReputations: DeployerReputationRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun submitReview(poolId: Long, label: String, notes: String?, reviewer: String): ReviewDecision {
        require(label in ReviewLabel.ALL) { "unknown label '$label'" }
        val pool = poolDetections.findById(poolId)
            .orElseThrow { IllegalArgumentException("pool $poolId not found") }

        val decision = reviewDecisions.save(ReviewDecision(
            poolId = poolId,
            reviewer = reviewer,
            label = label,
            notes = notes?.take(2000),
            reviewedAt = OffsetDateTime.now(),
        ))

        // Mark pool as reviewed so it leaves the queue
        pool.status = PoolDetectionStatus.REVIEWED
        poolDetections.save(pool)

        // Update deployer reputation
        if (pool.deployerAddress.isNotBlank()) {
            updateDeployerReputation(pool, label)
        }

        log.info("review submitted poolId={} deployer={} label={} reviewer={}",
            poolId, pool.deployerAddress, label, reviewer)

        return decision
    }

    private fun updateDeployerReputation(pool: PoolDetection, label: String) {
        val deployer = pool.deployerAddress.lowercase()
        val rep = deployerReputations.findById(deployer).orElseGet {
            DeployerReputation(address = deployer)
        }
        when (label) {
            ReviewLabel.SCAM -> rep.scamConfirmations += 1
            ReviewLabel.LEGITIMATE -> rep.legitimateAssociations += 1
            else -> {}  // uncertain / deferred do not move reputation
        }
        rep.lastUpdated = OffsetDateTime.now()
        deployerReputations.save(rep)
    }
}
