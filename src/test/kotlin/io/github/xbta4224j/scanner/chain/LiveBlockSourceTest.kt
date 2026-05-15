package io.github.xbta4224j.scanner.chain

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Int24
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.methods.response.Log
import java.math.BigInteger

class LiveBlockSourceTest {

    private val decoder = PoolCreatedDecoder()
    private val etherscan = mockk<EtherscanClient>()

    @Test
    fun `decode enriches the TokenContext with the deployer EOA from etherscan`() {
        every { etherscan.getContractCreation(NOVEL) } returns EtherscanClient.ContractCreation(
            contractAddress = NOVEL,
            contractCreator = DEPLOYER,
            txHash = "0x" + "9".repeat(64),
            blockNumber = 21_999_000,
            timestampSeconds = 1_700_000_000,
        )

        val source = LiveBlockSource(noWebSocketProvider(), decoder, etherscan)
        val rawLog = sampleLog(token0 = NOVEL, token1 = WETH, feeTier = 3000)

        val ctx = source.decode(rawLog)

        assertThat(ctx).isNotNull
        assertThat(ctx!!.tokenAddress).isEqualTo(NOVEL)
        assertThat(ctx.deployerAddress).isEqualTo(DEPLOYER)
        assertThat(ctx.pairedWithAddress).isEqualTo(WETH)
    }

    @Test
    fun `decode leaves deployer empty when etherscan returns null - never throws`() {
        every { etherscan.getContractCreation(any()) } returns null
        val source = LiveBlockSource(noWebSocketProvider(), decoder, etherscan)
        val rawLog = sampleLog(token0 = NOVEL, token1 = WETH, feeTier = 500)

        val ctx = source.decode(rawLog)

        assertThat(ctx).isNotNull
        assertThat(ctx!!.deployerAddress).isEmpty()
    }

    @Test
    fun `subscribe returns a Flow even with no WebSocket bean`() {
        val source = LiveBlockSource(noWebSocketProvider(), decoder, etherscan)
        // Calling subscribe() must not throw; the (empty) Flow is returned for
        // the IngestionPipeline to collect.
        val flow = source.subscribe()
        assertThat(flow).isNotNull
    }

    private fun noWebSocketProvider(): ObjectProvider<Web3j> {
        @Suppress("UNCHECKED_CAST")
        val provider = mockk<ObjectProvider<Web3j>>()
        every { provider.ifAvailable } returns null
        return provider
    }

    private fun sampleLog(token0: String, token1: String, feeTier: Int): Log = Log().apply {
        topics = listOf(
            PoolCreatedDecoder.POOL_CREATED_TOPIC,
            "0x" + token0.removePrefix("0x").padStart(64, '0'),
            "0x" + token1.removePrefix("0x").padStart(64, '0'),
            "0x" + BigInteger.valueOf(feeTier.toLong()).toString(16).padStart(64, '0'),
        )
        val fn = Function("_", listOf(Int24(BigInteger.valueOf(60)), Address(POOL)), emptyList())
        data = "0x" + FunctionEncoder.encode(fn).substring(10)
        setBlockNumber("0x1000000")
        blockHash = "0x" + "f".repeat(64)
        transactionHash = "0x" + "e".repeat(64)
    }

    companion object {
        const val WETH = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2"
        const val NOVEL = "0x1111111111111111111111111111111111111111"
        const val DEPLOYER = "0x2222222222222222222222222222222222222222"
        const val POOL = "0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
    }
}
