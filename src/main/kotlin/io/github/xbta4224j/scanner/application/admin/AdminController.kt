package io.github.xbta4224j.scanner.application.admin

import io.github.xbta4224j.scanner.query.HeuristicsConfig
import io.github.xbta4224j.scanner.application.PoolDetectionDto
import io.github.xbta4224j.scanner.indexing.PoolDetectionRepository
import io.github.xbta4224j.scanner.indexing.ReviewDecisionRepository
import io.github.xbta4224j.scanner.indexing.ReviewLabel
import org.springframework.data.domain.PageRequest
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Admin review queue. Lists detections at or above the high-risk threshold
 * that have not yet been labeled, plus the most recent decisions and a
 * deployer-reputation leaderboard.
 *
 * Authenticated via HTTP Basic (see SecurityConfig); REVIEWER role required.
 */
@Controller
class AdminController(
    private val poolDetections: PoolDetectionRepository,
    private val reviewDecisions: ReviewDecisionRepository,
    private val reviewService: ReviewService,
    private val config: HeuristicsConfig,
) {

    @GetMapping("/admin/queue")
    fun queue(model: Model): String {
        val highRisk = config.thresholds.highRisk
        // Top flagged-and-still-detected (i.e. not yet reviewed)
        val flagged = poolDetections
            .findByCompositeScoreGreaterThanEqualAndStatusOrderByCompositeScoreDescDetectedAtDesc(
                scoreFloor = highRisk,
                status = "detected",
                pageable = PageRequest.of(0, 100),
            )
            .map { PoolDetectionDto.from(it) }

        val recentReviews = reviewDecisions.findTop50ByOrderByReviewedAtDesc()

        model.addAttribute("flagged", flagged)
        model.addAttribute("highRiskThreshold", highRisk)
        model.addAttribute("recentReviews", recentReviews)
        model.addAttribute("counts", mapOf(
            "legitimate" to reviewDecisions.countByLabel(ReviewLabel.LEGITIMATE),
            "scam"       to reviewDecisions.countByLabel(ReviewLabel.SCAM),
            "uncertain"  to reviewDecisions.countByLabel(ReviewLabel.UNCERTAIN),
            "deferred"   to reviewDecisions.countByLabel(ReviewLabel.DEFERRED),
        ))
        return "admin/queue"
    }

    @PostMapping("/admin/review/{poolId}")
    fun submitReview(
        @PathVariable poolId: Long,
        @RequestParam label: String,
        @RequestParam(required = false) notes: String?,
        @AuthenticationPrincipal user: UserDetails?,
        model: Model,
    ): String {
        val reviewer = user?.username ?: "anonymous"
        reviewService.submitReview(poolId, label, notes, reviewer)
        return "redirect:/admin/queue"
    }
}
