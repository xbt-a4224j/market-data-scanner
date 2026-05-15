package io.github.xbta4224j.scanner.analysis.heuristics

import org.springframework.stereotype.Component

/**
 * STUB - real implementation lands in Issue #7 (the most sophisticated
 * implemented heuristic). Validation test against a known Etherscan-labeled
 * phishing factory is non-negotiable for credibility.
 *
 * Will enumerate the deployer EOA's prior ERC-20 contract deployments and
 * score each on lifetime, abandonment, and Etherscan label
 * (`Fake_Phishing*`, etc.). A serial scam-factory deployer fingerprint is the
 * strongest single chain-side signal we have.
 */
@Component
class DeployerHistoryHeuristic : StubHeuristic(
    name = "deployer_history",
    plannedSignal = "Deployer EOA's prior contract-deployment outcomes (lifetime, abandonment rate, Etherscan labels).",
)
