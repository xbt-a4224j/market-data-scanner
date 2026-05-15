package io.github.xbta4224j.scanner.ingestion

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.time.Instant

/**
 * Resolves a wall-clock instant to an Ethereum mainnet block number via
 * Etherscan's `block/getblocknobytime` API. Used by the admin backfill
 * trigger to convert "give me the last 7 days" into a block range.
 *
 * Returns null on API failure - the caller (admin controller) should respond
 * with a clear error rather than guess a block.
 */
@Component
class DateToBlockResolver(
    @Value("\${etherscan.api-key:}") private val apiKey: String,
    @Value("\${etherscan.base-url:https://api.etherscan.io/v2/api}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rest = RestClient.builder().baseUrl(baseUrl).build()
    private val mapper = ObjectMapper()

    /**
     * Returns the block number whose timestamp is closest to (and not after)
     * `at`. Etherscan calls this the "before" closest mode.
     */
    fun resolve(at: Instant): Long? {
        if (apiKey.isBlank()) {
            log.warn("etherscan api-key empty; DateToBlockResolver returning null")
            return null
        }
        return runCatching {
            val body = rest.get()
                .uri { uri ->
                    uri.queryParam("chainid", "1")
                        .queryParam("module", "block")
                        .queryParam("action", "getblocknobytime")
                        .queryParam("timestamp", at.epochSecond.toString())
                        .queryParam("closest", "before")
                        .queryParam("apikey", apiKey)
                        .build()
                }
                .retrieve()
                .body<String>() ?: return null
            val node = mapper.readTree(body)
            if (node["status"].asText() != "1") return null
            node["result"].asText().toLong()
        }.onFailure { log.warn("etherscan getBlockNoByTime({}) failed", at, it) }
            .getOrNull()
    }
}
