package io.github.xbta4224j.scanner.query.heuristics

import io.github.xbta4224j.scanner.ingestion.EtherscanClient
import io.github.xbta4224j.scanner.decoding.TokenContext
import io.github.xbta4224j.scanner.indexing.CorpusEntryDao
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.embedding.EmbeddingModel
import java.time.OffsetDateTime

/**
 * Pure-unit tests for the impersonation heuristic. Mocks EmbeddingModel +
 * CorpusEntryDao so we don't need OpenAI or pgvector in the test JVM.
 */
class MetadataSimilarityHeuristicTest {

    private val embeddings = mockk<EmbeddingModel>()
    private val corpus = mockk<CorpusEntryDao>()
    private val etherscan = mockk<EtherscanClient>()
    private val heuristic = MetadataSimilarityHeuristic(embeddings, corpus, etherscan)

    @Test
    fun `empty corpus returns low-confidence neutral with hint`() = runTest {
        every { corpus.count() } returns 0L

        val result = heuristic.evaluate(SAMPLE_TOKEN)

        assertThat(result.score).isEqualTo(0.5)
        assertThat(result.confidence).isEqualTo(0.0)
        assertThat(result.evidence["reason"]).isEqualTo("empty_corpus")
    }

    @Test
    fun `high cosine similarity + deployer mismatch fires impersonation alert`() = runTest {
        every { corpus.count() } returns 100L
        every { etherscan.callErc20StringMethod(any(), "0x95d89b41") } returns "USDC2"
        every { etherscan.callErc20StringMethod(any(), "0x06fdde03") } returns "USDC2"
        every { embeddings.embed(any<String>()) } returns floatArrayOf(0.1f, 0.2f, 0.3f)
        every { corpus.nearest(any(), any()) } returns listOf(
            CorpusEntryDao.Match(
                row = corpusRow(symbol = "USDC", canonicalDeployer = "0xCircleDeployer".lowercase()),
                cosineSimilarity = 0.95,
            )
        )

        val result = heuristic.evaluate(SAMPLE_TOKEN.copy(deployerAddress = "0xRandomScammer".lowercase()))

        assertThat(result.score).isEqualTo(0.92)
        assertThat(result.confidence).isEqualTo(0.85)
        assertThat(result.evidence["classification"]).isEqualTo("high_similarity_deployer_mismatch")
        assertThat(result.evidence["topMatchSymbol"]).isEqualTo("USDC")
    }

    @Test
    fun `high cosine similarity + deployer match (legitimate cross-deploy) scores low`() = runTest {
        every { corpus.count() } returns 100L
        every { etherscan.callErc20StringMethod(any(), any()) } returns "USDC.e"
        every { embeddings.embed(any<String>()) } returns floatArrayOf(0.1f, 0.2f, 0.3f)
        val sharedDeployer = "0xcircle".lowercase().padEnd(42, '0')
        every { corpus.nearest(any(), any()) } returns listOf(
            CorpusEntryDao.Match(
                row = corpusRow(symbol = "USDC", canonicalDeployer = sharedDeployer),
                cosineSimilarity = 0.94,
            )
        )

        val result = heuristic.evaluate(SAMPLE_TOKEN.copy(deployerAddress = sharedDeployer))

        assertThat(result.score).isEqualTo(0.05)
        assertThat(result.evidence["classification"]).isEqualTo("matches_canonical_deployer")
    }

    @Test
    fun `low similarity scores low risk - new token has no impersonation signal`() = runTest {
        every { corpus.count() } returns 100L
        every { etherscan.callErc20StringMethod(any(), any()) } returns "RANDOM"
        every { embeddings.embed(any<String>()) } returns floatArrayOf(0.1f, 0.2f, 0.3f)
        every { corpus.nearest(any(), any()) } returns listOf(
            CorpusEntryDao.Match(
                row = corpusRow(symbol = "USDC", canonicalDeployer = "0xcircle"),
                cosineSimilarity = 0.42,
            )
        )

        val result = heuristic.evaluate(SAMPLE_TOKEN)

        assertThat(result.score).isEqualTo(0.10)
        assertThat(result.evidence["classification"]).isEqualTo("no_clear_match")
    }

    @Test
    fun `embedding call failure returns low-confidence neutral`() = runTest {
        every { corpus.count() } returns 100L
        every { etherscan.callErc20StringMethod(any(), any()) } returns "X"
        every { embeddings.embed(any<String>()) } throws RuntimeException("openai 429")

        val result = heuristic.evaluate(SAMPLE_TOKEN)

        assertThat(result.score).isEqualTo(0.5)
        assertThat(result.confidence).isEqualTo(0.0)
        assertThat(result.evidence["reason"]).isEqualTo("embedding_call_failed")
    }

    private fun corpusRow(symbol: String, canonicalDeployer: String) = CorpusEntryDao.Row(
        id = 1,
        symbol = symbol,
        name = "$symbol Token",
        description = null,
        canonicalAddress = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
        canonicalDeployer = canonicalDeployer,
        chain = "ethereum",
        category = "stablecoin",
        source = "coingecko_top_100",
        addedAt = OffsetDateTime.now(),
    )

    companion object {
        val SAMPLE_TOKEN = TokenContext(
            tokenAddress = "0xnew000000000000000000000000000000000000",
            deployerAddress = "0xdeployer000000000000000000000000000000000",
            poolAddress = "0xpool0000000000000000000000000000000000000",
            pairedWithAddress = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",
            feeTier = 3000,
            blockNumber = 22_500_000,
            blockHash = "0x" + "f".repeat(64),
            blockTimestamp = OffsetDateTime.parse("2026-05-15T00:00:00Z"),
            txHash = "0x" + "e".repeat(64),
        )
    }
}
