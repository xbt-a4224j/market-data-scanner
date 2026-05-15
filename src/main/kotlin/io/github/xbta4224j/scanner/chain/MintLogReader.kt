package io.github.xbta4224j.scanner.chain

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.web3j.abi.EventEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Event
import org.web3j.abi.datatypes.generated.Int24
import org.web3j.abi.datatypes.generated.Uint128
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.methods.request.EthFilter
import java.math.BigInteger

/**
 * Reads the first Uniswap V3 `Mint` event for a given pool. Mint signature:
 *
 *   event Mint(
 *     address sender,
 *     address indexed owner,
 *     int24   indexed tickLower,
 *     int24   indexed tickUpper,
 *     uint128 amount,
 *     uint256 amount0,
 *     uint256 amount1
 *   );
 *
 * The `owner` topic identifies the holder credited with the LP position. If the
 * mint went via the NonfungiblePositionManager, `owner` is the NPM contract; the
 * actual end-user position needs ERC-721 Transfer tracing for the resulting
 * tokenId, which the LP-lock heuristic falls back on at "via_npm" confidence.
 */
interface MintLogReader {
    fun fetchFirstMint(poolAddress: String, fromBlock: Long, toBlock: Long): MintEvent?
}

data class MintEvent(
    val sender: String,
    val owner: String,
    val amount0: BigInteger,
    val amount1: BigInteger,
    val blockNumber: Long,
    val txHash: String,
)

@Component
class Web3jMintLogReader(
    private val web3j: Web3j,
) : MintLogReader {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun fetchFirstMint(poolAddress: String, fromBlock: Long, toBlock: Long): MintEvent? =
        runCatching {
            val filter = EthFilter(
                DefaultBlockParameter.valueOf(BigInteger.valueOf(fromBlock)),
                DefaultBlockParameter.valueOf(BigInteger.valueOf(toBlock)),
                poolAddress,
            ).addSingleTopic(MINT_TOPIC)

            val logs = web3j.ethGetLogs(filter).send().logs
            val first = logs.firstOrNull() ?: return@runCatching null
            val l = (first.get() as? org.web3j.protocol.core.methods.response.Log) ?: return@runCatching null
            if (l.topics.size != 4) return@runCatching null

            val owner = topicToAddress(l.topics[1])
            val nonIndexed = FunctionReturnDecoder.decode(l.data, MINT_EVENT.nonIndexedParameters)
            val sender = (nonIndexed[0].value as String).lowercase()
            val amount0 = nonIndexed[2].value as BigInteger
            val amount1 = nonIndexed[3].value as BigInteger
            MintEvent(
                sender = sender,
                owner = owner,
                amount0 = amount0,
                amount1 = amount1,
                blockNumber = l.blockNumber.toLong(),
                txHash = l.transactionHash.lowercase(),
            )
        }.onFailure { log.warn("eth_getLogs Mint scan for {} ({}..{}) failed", poolAddress, fromBlock, toBlock, it) }
            .getOrNull()

    private fun topicToAddress(topic: String): String =
        ("0x" + topic.removePrefix("0x").takeLast(40)).lowercase()

    companion object {
        val MINT_EVENT: Event = Event(
            "Mint",
            listOf(
                TypeReference.create(Address::class.java, false),    // sender (non-indexed)
                TypeReference.create(Address::class.java, true),     // owner (indexed)
                TypeReference.create(Int24::class.java, true),       // tickLower (indexed)
                TypeReference.create(Int24::class.java, true),       // tickUpper (indexed)
                TypeReference.create(Uint128::class.java, false),    // amount
                TypeReference.create(Uint256::class.java, false),    // amount0
                TypeReference.create(Uint256::class.java, false),    // amount1
            )
        )
        val MINT_TOPIC: String = EventEncoder.encode(MINT_EVENT)
    }
}
