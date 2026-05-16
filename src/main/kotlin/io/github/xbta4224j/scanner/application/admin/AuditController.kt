package io.github.xbta4224j.scanner.application.admin

import io.github.xbta4224j.scanner.indexing.DeployerReputationRepository
import io.github.xbta4224j.scanner.indexing.IngestionRunRepository
import io.github.xbta4224j.scanner.indexing.ReviewDecisionRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * /admin/audit - the human-facing audit trail. Combines:
 *   - the most recent review decisions (who labeled what, when)
 *   - the most recent ingestion runs (live + backfill, status, errors)
 *   - the deployer-reputation leaderboard (highest scam-confirmation count)
 */
@Controller
class AuditController(
    private val reviewDecisions: ReviewDecisionRepository,
    private val ingestionRuns: IngestionRunRepository,
    private val deployerReputations: DeployerReputationRepository,
) {

    @GetMapping("/admin/audit")
    fun audit(model: Model): String {
        model.addAttribute("decisions", reviewDecisions.findTop50ByOrderByReviewedAtDesc())
        model.addAttribute("runs", ingestionRuns.findTop20ByOrderByStartedAtDesc())
        model.addAttribute("topDeployers", deployerReputations.findTop20ByOrderByScamConfirmationsDesc())
        return "admin/audit"
    }
}
