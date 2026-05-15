package io.github.xbta4224j.scanner.analysis.heuristics

import io.github.xbta4224j.scanner.analysis.HeuristicResult
import io.github.xbta4224j.scanner.analysis.RiskHeuristic
import io.github.xbta4224j.scanner.chain.EtherscanClient
import io.github.xbta4224j.scanner.chain.TokenContext
import io.github.xbta4224j.scanner.persistence.CorpusEntryDao
import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * Impersonation detector. Embeds the new token's symbol+name and runs cosine
 * similarity against the legitimate-token pgvector corpus seeded by
 * [io.github.xbta4224j.scanner.corpus.CorpusIngestionService].
 *
 * The signal is NOT "the new token is similar to a known token" by itself
 * (legitimate tokens often share themes - many "wrapped X" tokens exist).
 * The signal is "the new token is similar to a known token AND the deployer
 * does NOT match the canonical deployer of that known token". That is the
 * impersonation pattern: someone deploys a token named "USDC2" or "uSDC"
 * from a wallet that has nothing to do with Circle.
 *
 * Score formula:
 *   - top similarity > 0.92 AND deployer mismatch -> 0.92, conf 0.85
 *     (very close embedding to a canonical token, deployed by someone else)
 *   - top similarity > 0.80 AND deployer mismatch -> 0.65, conf 0.70
 *   - top similarity > 0.92 AND deployer matches  -> 0.05, conf 0.85
 *     (probably the same project re-deploying a paired token)
 *   - everything else -> 0.10, conf 0.50
 *
 * If the corpus is empty (Issue #19 hasn't been run yet) the heuristic
 * returns confidence=0 so it doesn't sway the composite.
 */
@Component
class MetadataSimilarityHeuristic(
    private val embeddings: EmbeddingModel,
    private val corpus: CorpusEntryDao,
    private val etherscan: EtherscanClient,
) : RiskHeuristic {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name: String = "metadata_similarity"
    override val version: String = "1.0.0"

    override suspend fun evaluate(token: TokenContext): HeuristicResult {
        val corpusSize = corpus.count()
        val inputsHash = inputsHash(token.tokenAddress, corpusSize)

        if (corpusSize == 0L) {
            return HeuristicResult(
                heuristicName = name,
                score = 0.5,
                confidence = 0.0,
                evidence = mapOf("reason" to "empty_corpus", "hint" to "POST /admin/corpus/refresh to populate"),
                heuristicVersion = version,
                inputsHash = inputsHash,
            )
        }

        // Read symbol + name on-chain for the new token. We do not have these
        // in TokenContext (which only has addresses), so we call Etherscan to
        // get the verified-source token info if available; fall back to just
        // the address as embedding input if not.
        val symbol = readSymbol(token.tokenAddress) ?: ""
        val nameOnChain = readName(token.tokenAddress) ?: ""
        val embeddingInput = "Token $symbol ($nameOnChain). Newly deployed at ${token.tokenAddress}."

        val embedding = runCatching { embeddings.embed(embeddingInput) }.getOrElse { err ->
            log.warn("embedding call failed for {}: {}", token.tokenAddress, err.message)
            return HeuristicResult(
                heuristicName = name,
                score = 0.5,
                confidence = 0.0,
                evidence = mapOf("reason" to "embedding_call_failed"),
                heuristicVersion = version,
                inputsHash = inputsHash,
            )
        }

        val matches = corpus.nearest(embedding, k = 3)
        val top = matches.firstOrNull()
            ?: return HeuristicResult(
                heuristicName = name,
                score = 0.1,
                confidence = 0.5,
                evidence = mapOf("reason" to "no_matches", "corpusSize" to corpusSize),
                heuristicVersion = version,
                inputsHash = inputsHash,
            )

        val deployerMatches = top.row.canonicalDeployer.equals(token.deployerAddress, ignoreCase = true)

        val (score, confidence, classification) = when {
            top.cosineSimilarity > 0.92 && !deployerMatches ->
                Triple(0.92, 0.85, "high_similarity_deployer_mismatch")
            top.cosineSimilarity > 0.80 && !deployerMatches ->
                Triple(0.65, 0.70, "moderate_similarity_deployer_mismatch")
            top.cosineSimilarity > 0.92 && deployerMatches ->
                Triple(0.05, 0.85, "matches_canonical_deployer")
            else -> Triple(0.10, 0.50, "no_clear_match")
        }

        return HeuristicResult(
            heuristicName = name,
            score = score,
            confidence = confidence,
            evidence = mapOf(
                "classification" to classification,
                "topSimilarity" to top.cosineSimilarity,
                "topMatchSymbol" to top.row.symbol,
                "topMatchName" to top.row.name,
                "topMatchAddress" to top.row.canonicalAddress,
                "topMatchDeployer" to top.row.canonicalDeployer,
                "deployerMatches" to deployerMatches,
                "newTokenSymbol" to symbol,
                "newTokenName" to nameOnChain,
                "corpusSize" to corpusSize,
                "secondMatch" to (matches.getOrNull(1)?.let {
                    mapOf("symbol" to it.row.symbol, "similarity" to it.cosineSimilarity)
                } ?: emptyMap<String, Any>()),
            ),
            heuristicVersion = version,
            inputsHash = inputsHash,
        )
    }

    private fun readSymbol(addr: String): String? = etherscan.callErc20StringMethod(addr, "0x95d89b41")  // symbol()
    private fun readName(addr: String): String? = etherscan.callErc20StringMethod(addr, "0x06fdde03")    // name()

    private fun inputsHash(tokenAddr: String, corpusSize: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("$tokenAddr|$corpusSize".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
