package io.github.xbta4224j.scanner.warehouse

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.xbta4224j.scanner.ingestion.EtherscanClient
import io.github.xbta4224j.scanner.indexing.CorpusEntryDao
import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

/**
 * Pulls top-N legitimate tokens by market cap from CoinGecko, verifies each
 * has an Ethereum-mainnet contract address, resolves the canonical deployer
 * via Etherscan, embeds the metadata via Spring AI, and upserts to the
 * pgvector `corpus_entries` table.
 *
 * Re-runnable: every call upserts on `canonical_address` so the corpus stays
 * fresh as new tokens enter the trusted set. Cost is dominated by OpenAI
 * embedding calls (~$0.0002 for top 100 tokens).
 *
 * The full-pipeline version this stub anticipates would also pull from
 * Etherscan's verified-contract dataset and any internal labeled-legitimate
 * sources; for v1 we have CoinGecko + Etherscan-creation as the two
 * authoritative sources.
 */
@Service
class CorpusIngestionService(
    private val embeddings: EmbeddingModel,
    private val etherscan: EtherscanClient,
    private val dao: CorpusEntryDao,
    @Value("\${corpus.coingecko.base-url:https://api.coingecko.com/api/v3}") private val coingeckoBase: String,
    @Value("\${corpus.coingecko.top-n:100}") private val topN: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()
    private val rest = RestClient.builder().build()

    data class IngestSummary(
        val candidatesConsidered: Int,
        val ethereumPlatformHits: Int,
        val deployerResolved: Int,
        val embedded: Int,
        val skippedExisting: Int,
        val errors: Int,
    )

    fun ingestTopN(limit: Int = topN): IngestSummary {
        log.info("corpus ingestion starting: target top-{} CoinGecko tokens", limit)

        val markets = fetchTopMarkets(limit)
        val platforms = fetchPlatforms()
        val candidates = markets.mapNotNull { m ->
            val ethAddr = platforms[m.id]?.lowercase()?.takeIf { it.startsWith("0x") && it.length == 42 }
            ethAddr?.let { Candidate(it, m.symbol.uppercase(), m.name, m.rank) }
        }
        log.info("  {} of top-{} markets have an ethereum contract", candidates.size, markets.size)

        var embedded = 0
        var deployerResolved = 0
        var errors = 0

        for (c in candidates) {
            try {
                // Etherscan free tier: 5 calls / sec. The cached version of
                // getContractCreation is fast on a re-run; on a cold corpus
                // ingest, the natural pacing keeps us comfortably under the
                // limit and avoids the 1-minute lockout if we burst.
                Thread.sleep(THROTTLE_MS)
                val creation = etherscan.getContractCreation(c.address)
                if (creation == null) {
                    log.debug("  no creation tx for {}, skipping", c.address)
                    continue
                }
                deployerResolved++

                val text = buildEmbeddingInput(c)
                val vec = embeddings.embed(text)
                dao.upsert(
                    symbol = c.symbol.take(32),
                    name = c.name.take(128),
                    description = "rank ${c.rank} per CoinGecko top-cap",
                    canonicalAddress = c.address,
                    canonicalDeployer = creation.contractCreator,
                    category = categorize(c.symbol, c.name),
                    embedding = vec,
                    source = "coingecko_top_$limit",
                    addedBy = "corpus-ingestion-service",
                )
                embedded++
                if (embedded % 10 == 0) log.info("  embedded {}/{}", embedded, candidates.size)
            } catch (e: Exception) {
                log.warn("  failed to ingest {}: {}", c.address, e.message)
                errors++
            }
        }

        val summary = IngestSummary(
            candidatesConsidered = markets.size,
            ethereumPlatformHits = candidates.size,
            deployerResolved = deployerResolved,
            embedded = embedded,
            skippedExisting = 0,
            errors = errors,
        )
        log.info("corpus ingestion done: {}", summary)
        return summary
    }

    private data class Candidate(val address: String, val symbol: String, val name: String, val rank: Int)
    private data class Market(val id: String, val symbol: String, val name: String, val rank: Int)

    private companion object {
        // ~4 req/s, well under Etherscan's 5/s free-tier limit and CoinGecko's
        // ~30/min public-tier limit (corpus ingest only hits CoinGecko twice
        // per run; the loop body is the Etherscan-heavy path).
        const val THROTTLE_MS = 250L
    }

    private fun fetchTopMarkets(limit: Int): List<Market> {
        val capped = minOf(limit, 250)
        val url = "$coingeckoBase/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=$capped&page=1&sparkline=false"
        val body: String = rest.get().uri(url).retrieve().body<String>() ?: return emptyList()
        val arr = mapper.readTree(body)
        return arr.mapNotNull { node ->
            Market(
                id = node["id"]?.asText() ?: return@mapNotNull null,
                symbol = node["symbol"]?.asText() ?: return@mapNotNull null,
                name = node["name"]?.asText() ?: return@mapNotNull null,
                rank = node["market_cap_rank"]?.asInt() ?: 0,
            )
        }
    }

    private fun fetchPlatforms(): Map<String, String> {
        val url = "$coingeckoBase/coins/list?include_platform=true"
        val body: String = rest.get().uri(url).retrieve().body<String>() ?: return emptyMap()
        val arr = mapper.readTree(body)
        return arr.mapNotNull { node ->
            val id = node["id"]?.asText() ?: return@mapNotNull null
            val eth = node["platforms"]?.get("ethereum")?.asText()?.takeIf { it.isNotBlank() }
            eth?.let { id to it }
        }.toMap()
    }

    private fun buildEmbeddingInput(c: Candidate): String =
        // The embedding is what the impersonation heuristic compares against,
        // so we emphasize symbol + name + a short context clause. Keeping
        // input short keeps cost down (text-embedding-3-small charges per token).
        "Token ${c.symbol} (${c.name}). Established cryptocurrency with market-cap rank ${c.rank}."

    private fun categorize(symbol: String, name: String): String {
        val s = symbol.lowercase()
        val n = name.lowercase()
        return when {
            s in setOf("usdc","usdt","dai","fdusd","tusd","frax","usde","lusd","gusd","pyusd","usdp","usds") -> "stablecoin"
            s in setOf("wbtc","tbtc","weth","steth","wsteth","reth","cbeth") -> "wrapped_or_lst"
            s in setOf("uni","mkr","aave","comp","ldo","ena","ondo","crv","snx","bal","yfi","1inch","pendle") -> "governance"
            s in setOf("shib","pepe","doge","floki","mog","wif","bonk","neiro","popcat") -> "meme_established"
            "stable" in n -> "stablecoin"
            else -> "bluechip_defi"
        }
    }
}
