package io.github.xbta4224j.scanner.analysis

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Externalized heuristics configuration. Bound from the `heuristics.*` block in
 * application.yml. Bean is registered via @ConfigurationPropertiesScan on the
 * ScannerApplication entrypoint.
 *
 * `weights` keys must match each RiskHeuristic.name. A heuristic with no entry
 * here counts as weight zero in the composite (i.e. runs and produces evidence
 * but does not influence the score - useful for shipping new heuristics dark).
 */
@ConfigurationProperties(prefix = "heuristics")
data class HeuristicsConfig(
    val weights: Map<String, Double> = emptyMap(),
    val thresholds: Thresholds = Thresholds(),
) {
    data class Thresholds(
        /** Composite score above this routes the detection into the admin review queue. */
        val highRisk: Int = 70,
        /** Per-heuristic confidence floor for "decisive" alerts. */
        val highConfidence: Double = 0.85,
    )
}
