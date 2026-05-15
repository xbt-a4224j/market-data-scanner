package io.github.xbta4224j.scanner.chain

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.web3j.abi.EventEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Event
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.methods.request.EthFilter
import java.math.BigInteger

/**
 * Fetches ERC-20 `Transfer(address indexed from, address indexed to, uint256 value)`
 * events for a given token between two block heights.
 *
 * Reads via the HTTP `Web3j` bean (one shot per heuristic invocation; the WSS
 * subscription is reserved for live block detection only).
 *
 * Returns an empty list and logs a warning on RPC failure - heuristics treat
 * "no transfers found" as low-confidence rather than throwing.
 */
interface TransferLogReader {
    fun fetchTransfers(tokenAddress: String, fromBlock: Long, toBlock: Long): List<Transfer>
}

data class Transfer(
    val from: String,
    val to: String,
    val value: BigInteger,
    val blockNumber: Long,
)

@Component
class Web3jTransferLogReader(
    private val web3j: Web3j,
) : TransferLogReader {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun fetchTransfers(tokenAddress: String, fromBlock: Long, toBlock: Long): List<Transfer> =
        runCatching {
            val filter = EthFilter(
                DefaultBlockParameter.valueOf(BigInteger.valueOf(fromBlock)),
                DefaultBlockParameter.valueOf(BigInteger.valueOf(toBlock)),
                tokenAddress,
            ).addSingleTopic(TRANSFER_TOPIC)

            web3j.ethGetLogs(filter).send().logs.mapNotNull { logResult ->
                val l = (logResult.get() as? org.web3j.protocol.core.methods.response.Log) ?: return@mapNotNull null
                if (l.topics.size != 3) return@mapNotNull null
                Transfer(
                    from = topicToAddress(l.topics[1]),
                    to = topicToAddress(l.topics[2]),
                    value = BigInteger(l.data.removePrefix("0x"), 16),
                    blockNumber = l.blockNumber.toLong(),
                )
            }
        }.onFailure { log.warn("ethGetLogs Transfer scan for {} ({}..{}) failed", tokenAddress, fromBlock, toBlock, it) }
            .getOrDefault(emptyList())

    private fun topicToAddress(topic: String): String =
        ("0x" + topic.removePrefix("0x").takeLast(40)).lowercase()

    companion object {
        val TRANSFER_EVENT: Event = Event(
            "Transfer",
            listOf(
                TypeReference.create(Address::class.java, true),
                TypeReference.create(Address::class.java, true),
                TypeReference.create(Uint256::class.java, false),
            )
        )
        val TRANSFER_TOPIC: String = EventEncoder.encode(TRANSFER_EVENT)
    }
}
