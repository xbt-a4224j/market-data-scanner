package io.github.xbta4224j.scanner.query

import io.github.xbta4224j.scanner.decoding.TokenContext
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class CompositeScorerTest {

    @Test
    fun `confidence-weighted average across two heuristics`() = runTest {
        val scorer = CompositeScorer(
            heuristics = listOf(
                fake("a", score = 0.8, confidence = 1.0),
                fake("b", score = 0.4, confidence = 1.0),
            ),
            config = configWith("a" to 0.5, "b" to 0.5),
        )

        val result = scorer.score(SAMPLE_TOKEN)

        // numerator   = 0.5*1*0.8 + 0.5*1*0.4 = 0.40 + 0.20 = 0.60
        // denominator = 0.5*1     + 0.5*1     = 1.00
        // composite   = 0.60 * 100 = 60
        assertThat(result.composite).isEqualTo(60)
    }

    @Test
    fun `unequal weights bias the composite toward the higher-weighted heuristic`() = runTest {
        val scorer = CompositeScorer(
            heuristics = listOf(
                fake("loud", score = 0.9, confidence = 1.0),
                fake("quiet", score = 0.1, confidence = 1.0),
            ),
            config = configWith("loud" to 0.8, "quiet" to 0.2),
        )

        val result = scorer.score(SAMPLE_TOKEN)

        // numerator   = 0.8*1*0.9 + 0.2*1*0.1 = 0.72 + 0.02 = 0.74
        // denominator = 0.8 + 0.2             = 1.00
        // composite   = 74
        assertThat(result.composite).isEqualTo(74)
    }

    @Test
    fun `low-confidence heuristic does not drag the composite`() = runTest {
        val scorer = CompositeScorer(
            heuristics = listOf(
                fake("strong", score = 0.9, confidence = 1.0),
                fake("weak", score = 0.1, confidence = 0.0),  // stub-style
            ),
            config = configWith("strong" to 0.5, "weak" to 0.5),
        )

        val result = scorer.score(SAMPLE_TOKEN)

        // The weak heuristic contributes 0 to numerator and 0 to denominator,
        // so the composite is purely the strong one: 0.9 * 100 = 90.
        assertThat(result.composite).isEqualTo(90)
    }

    @Test
    fun `all-stub composite returns the neutral fallback`() = runTest {
        val scorer = CompositeScorer(
            heuristics = listOf(
                fake("a", score = 0.99, confidence = 0.0),
                fake("b", score = 0.01, confidence = 0.0),
            ),
            config = configWith("a" to 0.5, "b" to 0.5),
        )

        val result = scorer.score(SAMPLE_TOKEN)

        assertThat(result.composite).isEqualTo(CompositeScorer.NEUTRAL_COMPOSITE)
        assertThat(result.totalConfidenceWeight).isEqualTo(0.0)
    }

    @Test
    fun `heuristic with no weight entry counts as weight zero`() = runTest {
        val scorer = CompositeScorer(
            heuristics = listOf(
                fake("known", score = 0.6, confidence = 1.0),
                fake("dark_launch", score = 0.95, confidence = 1.0),
            ),
            config = configWith("known" to 1.0),
        )

        val result = scorer.score(SAMPLE_TOKEN)

        // dark_launch's weight = 0, so it doesn't contribute.
        // composite = (1.0 * 1.0 * 0.6) / (1.0 * 1.0) * 100 = 60
        assertThat(result.composite).isEqualTo(60)
    }

    @Test
    fun `throwing heuristic falls back to confidence zero and others still score`() = runTest {
        val thrower = object : RiskHeuristic {
            override val name = "broken"
            override val version = "1.0.0"
            override suspend fun evaluate(token: TokenContext): HeuristicResult =
                throw RuntimeException("rpc dropped")
        }
        val scorer = CompositeScorer(
            heuristics = listOf(
                fake("ok", score = 0.7, confidence = 1.0),
                thrower,
            ),
            config = configWith("ok" to 0.5, "broken" to 0.5),
        )

        val result = scorer.score(SAMPLE_TOKEN)

        // broken contributes confidence 0 -> composite = 0.7 * 100 = 70.
        assertThat(result.composite).isEqualTo(70)
        assertThat(result.results.single { it.heuristicName == "broken" }.evidence)
            .containsKey("error")
    }

    @Test
    fun `composite is clamped to the 0-to-100 range`() = runTest {
        val scorer = CompositeScorer(
            heuristics = listOf(fake("x", score = 1.0, confidence = 1.0)),
            config = configWith("x" to 1.0),
        )
        assertThat(scorer.score(SAMPLE_TOKEN).composite).isEqualTo(100)
    }

    private fun configWith(vararg weights: Pair<String, Double>) =
        HeuristicsConfig(weights = mapOf(*weights))

    private fun fake(name: String, score: Double, confidence: Double) = object : RiskHeuristic {
        override val name = name
        override val version = "1.0.0-fake"
        override suspend fun evaluate(token: TokenContext) = HeuristicResult(
            heuristicName = name,
            score = score,
            confidence = confidence,
            evidence = mapOf("fake" to true),
            heuristicVersion = version,
            inputsHash = "fake",
        )
    }

    companion object {
        val SAMPLE_TOKEN = TokenContext(
            tokenAddress = "0x" + "a".repeat(40),
            deployerAddress = "0x" + "b".repeat(40),
            poolAddress = "0x" + "c".repeat(40),
            pairedWithAddress = "0x" + "0".repeat(40),
            feeTier = 3000,
            blockNumber = 22_500_000,
            blockHash = "0x" + "f".repeat(64),
            blockTimestamp = OffsetDateTime.parse("2026-05-14T00:00:00Z"),
            txHash = "0x" + "1".repeat(64),
        )
    }
}
