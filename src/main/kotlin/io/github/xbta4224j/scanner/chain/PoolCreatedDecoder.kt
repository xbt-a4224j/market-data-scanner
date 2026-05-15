package io.github.xbta4224j.scanner.chain

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.web3j.abi.EventEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Event
import org.web3j.abi.datatypes.generated.Int24
import org.web3j.abi.datatypes.generated.Uint24
import org.web3j.protocol.core.methods.response.Log
import java.math.BigInteger
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Decodes Uniswap V3 factory `PoolCreated` events into [TokenContext]s.
 *
 * Event signature:
 *   event PoolCreated(
 *     address indexed token0,
 *     address indexed token1,
 *     uint24  indexed fee,
 *     int24            tickSpacing,
 *     address          pool
 *   );
 *
 * The "novel" token side of the pair is the one that is NOT a known stablecoin
 * or wrapped-ETH. If both sides are known (e.g. a USDC/WETH pool created for
 * routing), the event is skipped - those are not "new token launches".
 */
@Component
class PoolCreatedDecoder {

    private val log = LoggerFactory.getLogger(javaClass)

    fun decode(rawLog: Log, blockTimestampSeconds: Long? = null): TokenContext? {
        if (rawLog.topics.size != 4) {
            log.debug("skipping log with unexpected topic count {} at tx {}", rawLog.topics.size, rawLog.transactionHash)
            return null
        }
        if (!rawLog.topics[0].equals(POOL_CREATED_TOPIC, ignoreCase = true)) {
            return null
        }

        val token0 = decodeAddressTopic(rawLog.topics[1])
        val token1 = decodeAddressTopic(rawLog.topics[2])
        val fee = decodeUint24Topic(rawLog.topics[3])

        val (novel, paired) = pickNovel(token0, token1) ?: run {
            log.debug("both sides ({} / {}) are well-known, skipping pool", token0, token1)
            return null
        }

        val nonIndexed = FunctionReturnDecoder.decode(rawLog.data, EVENT.nonIndexedParameters)
        // nonIndexed[1].value is a String (Address.getValue() returns the hex string).
        val poolAddress = (nonIndexed[1].value as String).lowercase()

        val ts = blockTimestampSeconds?.let { OffsetDateTime.ofInstant(Instant.ofEpochSecond(it), ZoneOffset.UTC) }
            ?: OffsetDateTime.now(ZoneOffset.UTC)

        return TokenContext(
            tokenAddress = novel,
            deployerAddress = "",  // populated by EtherscanClient enrichment in LiveBlockSource
            poolAddress = poolAddress,
            pairedWithAddress = paired,
            feeTier = fee,
            blockNumber = rawLog.blockNumber.toLong(),
            blockHash = rawLog.blockHash.lowercase(),
            blockTimestamp = ts,
            txHash = rawLog.transactionHash.lowercase(),
        )
    }

    private fun pickNovel(token0: String, token1: String): Pair<String, String>? {
        val t0Known = token0 in WELL_KNOWN_TOKENS
        val t1Known = token1 in WELL_KNOWN_TOKENS
        return when {
            !t0Known && t1Known -> token0 to token1
            t0Known && !t1Known -> token1 to token0
            !t0Known && !t1Known -> token0 to token1   // exotic pair; pick token0 by convention
            else -> null  // both known - not a "new token launch" event
        }
    }

    private fun decodeAddressTopic(topic: String): String =
        // 32-byte topic; address is the last 20 bytes (40 hex chars after 0x).
        ("0x" + topic.removePrefix("0x").takeLast(40)).lowercase()

    private fun decodeUint24Topic(topic: String): Int =
        BigInteger(topic.removePrefix("0x"), 16).toInt()

    companion object {
        const val UNISWAP_V3_FACTORY = "0x1F98431c8aD98523631AE4a59f267346ea31F984"

        // Lowercased canonical mainnet addresses of tokens to exclude from "novel" detection.
        val WELL_KNOWN_TOKENS: Set<String> = setOf(
            "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",  // WETH
            "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",  // USDC
            "0xdac17f958d2ee523a2206206994597c13d831ec7",  // USDT
            "0x6b175474e89094c44da98b954eedeac495271d0f",  // DAI
        )

        val EVENT: Event = Event(
            "PoolCreated",
            listOf(
                TypeReference.create(Address::class.java, true),
                TypeReference.create(Address::class.java, true),
                TypeReference.create(Uint24::class.java, true),
                TypeReference.create(Int24::class.java, false),
                TypeReference.create(Address::class.java, false),
            )
        )

        val POOL_CREATED_TOPIC: String = EventEncoder.encode(EVENT)
    }
}
