package io.github.xbta4224j.scanner.decoding

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Int24
import org.web3j.protocol.core.methods.response.Log
import java.math.BigInteger

class PoolCreatedDecoderTest {

    private val decoder = PoolCreatedDecoder()

    @Test
    fun `decodes a NEW_TOKEN-WETH pool, picks NEW_TOKEN as novel`() {
        val log = poolCreatedLog(
            token0 = NEW_TOKEN,
            token1 = WETH,
            feeTier = 3000,
            tickSpacing = 60,
            pool = POOL_ADDR,
            blockNumber = 22_500_001,
            blockHash = "0x" + "a".repeat(64),
            txHash = "0x" + "b".repeat(64),
        )

        val ctx = decoder.decode(log)

        assertThat(ctx).isNotNull
        assertThat(ctx!!.tokenAddress).isEqualTo(NEW_TOKEN)
        assertThat(ctx.pairedWithAddress).isEqualTo(WETH)
        assertThat(ctx.poolAddress).isEqualTo(POOL_ADDR)
        assertThat(ctx.feeTier).isEqualTo(3000)
        assertThat(ctx.blockNumber).isEqualTo(22_500_001)
        assertThat(ctx.deployerAddress).isEmpty()  // populated by LiveBlockSource enrichment
    }

    @Test
    fun `swaps token0-token1 if WETH happens to be token0 (lower address)`() {
        val log = poolCreatedLog(
            token0 = WETH,
            token1 = NEW_TOKEN,
            feeTier = 500,
            tickSpacing = 10,
            pool = POOL_ADDR,
        )

        val ctx = decoder.decode(log)

        assertThat(ctx).isNotNull
        assertThat(ctx!!.tokenAddress).isEqualTo(NEW_TOKEN)
        assertThat(ctx.pairedWithAddress).isEqualTo(WETH)
    }

    @Test
    fun `skips a USDC-WETH routing pool (both sides well-known)`() {
        val log = poolCreatedLog(
            token0 = USDC,
            token1 = WETH,
            feeTier = 500,
            tickSpacing = 10,
            pool = POOL_ADDR,
        )

        assertThat(decoder.decode(log)).isNull()
    }

    @Test
    fun `decodes an exotic NEW-NEW pool, picks token0 by convention`() {
        val log = poolCreatedLog(
            token0 = NEW_TOKEN,
            token1 = OTHER_NEW_TOKEN,
            feeTier = 10000,
            tickSpacing = 200,
            pool = POOL_ADDR,
        )

        val ctx = decoder.decode(log)

        assertThat(ctx).isNotNull
        assertThat(ctx!!.tokenAddress).isEqualTo(NEW_TOKEN)
        assertThat(ctx.pairedWithAddress).isEqualTo(OTHER_NEW_TOKEN)
    }

    @Test
    fun `returns null for a log whose topic0 is not PoolCreated`() {
        val log = Log().apply {
            topics = listOf(
                "0x" + "9".repeat(64),  // wrong event sig
                paddedAddress(NEW_TOKEN),
                paddedAddress(WETH),
                "0x" + "0".repeat(58) + "000bb8",
            )
            data = "0x"
            setBlockNumber("0x1000")
            blockHash = "0x" + "c".repeat(64)
            transactionHash = "0x" + "d".repeat(64)
        }

        assertThat(decoder.decode(log)).isNull()
    }

    @Test
    fun `passes block timestamp through when provided`() {
        val log = poolCreatedLog(
            token0 = NEW_TOKEN,
            token1 = WETH,
            feeTier = 3000,
            tickSpacing = 60,
            pool = POOL_ADDR,
        )

        val ctx = decoder.decode(log, blockTimestampSeconds = 1_700_000_000)

        assertThat(ctx).isNotNull
        assertThat(ctx!!.blockTimestamp.toEpochSecond()).isEqualTo(1_700_000_000)
    }

    private fun poolCreatedLog(
        token0: String,
        token1: String,
        feeTier: Int,
        tickSpacing: Int,
        pool: String,
        blockNumber: Long = 22_000_000,
        blockHash: String = "0x" + "f".repeat(64),
        txHash: String = "0x" + "e".repeat(64),
    ): Log = Log().apply {
        topics = listOf(
            PoolCreatedDecoder.POOL_CREATED_TOPIC,
            paddedAddress(token0),
            paddedAddress(token1),
            paddedUint24(feeTier),
        )
        data = encodeNonIndexed(tickSpacing, pool)
        setBlockNumber("0x" + blockNumber.toString(16))
        this.blockHash = blockHash
        this.transactionHash = txHash
    }

    private fun paddedAddress(addr: String): String =
        "0x" + addr.removePrefix("0x").lowercase().padStart(64, '0')

    private fun paddedUint24(v: Int): String =
        "0x" + BigInteger.valueOf(v.toLong()).toString(16).padStart(64, '0')

    private fun encodeNonIndexed(tickSpacing: Int, pool: String): String {
        val fn = Function(
            "_",
            listOf(Int24(BigInteger.valueOf(tickSpacing.toLong())), Address(pool)),
            emptyList()
        )
        // FunctionEncoder includes a 4-byte selector; we want raw params only.
        val encoded = FunctionEncoder.encode(fn)
        return "0x" + encoded.substring(10)
    }

    companion object {
        const val WETH = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2"
        const val USDC = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
        const val NEW_TOKEN = "0x1234567890abcdef1234567890abcdef12345678"
        const val OTHER_NEW_TOKEN = "0xfedcba9876543210fedcba9876543210fedcba98"
        const val POOL_ADDR = "0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
    }
}
