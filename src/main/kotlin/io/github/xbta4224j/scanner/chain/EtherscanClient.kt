package io.github.xbta4224j.scanner.chain

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

/**
 * Thin wrapper over the Etherscan V2 multi-chain API. Used for:
 *  - resolving the deployer EOA of a token contract (enriches TokenContext)
 *  - looking up Etherscan's labeled name-tag (e.g. "Fake_Phishing*") for
 *    DeployerHistoryHeuristic (Issue #7)
 *
 * Spring's @Cacheable wraps each lookup with the Caffeine cache configured in
 * application.yml: same address queried within 5min returns the cached result.
 *
 * Failure mode: returns null (with a warn log) rather than throwing. Callers
 * treat null as "could not resolve" and lower their confidence accordingly.
 */
@Component
class EtherscanClient(
    @Value("\${etherscan.api-key:}") private val apiKey: String,
    @Value("\${etherscan.base-url:https://api.etherscan.io/v2/api}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rest = RestClient.builder().baseUrl(baseUrl).build()
    private val mapper = ObjectMapper()

    @Cacheable("deployer-history", unless = "#result == null")
    fun getContractCreation(contractAddress: String): ContractCreation? {
        if (apiKey.isBlank()) {
            log.warn("etherscan api-key is empty; getContractCreation({}) will return null", contractAddress)
            return null
        }
        return runCatching {
            val body: String = rest.get()
                .uri { uriBuilder ->
                    uriBuilder
                        .queryParam("chainid", "1")
                        .queryParam("module", "contract")
                        .queryParam("action", "getcontractcreation")
                        .queryParam("contractaddresses", contractAddress)
                        .queryParam("apikey", apiKey)
                        .build()
                }
                .retrieve()
                .body<String>() ?: return null
            val node = mapper.readTree(body)
            if (node["status"].asText() != "1") return null
            val first = node["result"]?.takeIf { it.isArray && it.size() > 0 }?.get(0) ?: return null
            ContractCreation(
                contractAddress = first["contractAddress"].asText().lowercase(),
                contractCreator = first["contractCreator"].asText().lowercase(),
                txHash = first["txHash"].asText().lowercase(),
                blockNumber = first["blockNumber"].asText().toLong(),
                timestampSeconds = first["timestamp"].asText().toLong(),
            )
        }.onFailure { log.warn("etherscan getContractCreation({}) failed", contractAddress, it) }
            .getOrNull()
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ContractCreation(
        val contractAddress: String,
        val contractCreator: String,
        val txHash: String,
        val blockNumber: Long,
        val timestampSeconds: Long,
    )
}
