package io.github.xbta4224j.scanner.query.heuristics

import io.github.xbta4224j.scanner.decoding.MintEvent
import io.github.xbta4224j.scanner.decoding.MintLogReader
import io.github.xbta4224j.scanner.decoding.NpmPositionTracer
import io.github.xbta4224j.scanner.ingestion.PriceOracle
import io.github.xbta4224j.scanner.decoding.TokenContext
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigInteger
import java.time.OffsetDateTime

class LpLockHeuristicTest {

    private val reader = mockk<MintLogReader>()
    private val priceOracle = mockk<PriceOracle>(relaxed = true) {
        every { valueLpSide(any(), any()) } returns null
    }
    private val npmTracer = mockk<NpmPositionTracer> {
        every { traceNpmRecipient(any()) } returns null
    }
    private val heuristic = LpLockHeuristic(reader, priceOracle, npmTracer)

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("scenarios")
    fun `classifies LP destination per fixture row and scores in the expected band`(
        scenario: String, expectedClassification: String, scoreMin: Double, scoreMax: Double,
        owner: String, sender: String, amount0: BigInteger, amount1: BigInteger,
    ) = runTest {
        every {
            reader.fetchFirstMint(SAMPLE_TOKEN.poolAddress, any(), any())
        } returns MintEvent(
            sender = sender,
            owner = owner,
            amount0 = amount0,
            amount1 = amount1,
            blockNumber = SAMPLE_TOKEN.blockNumber + 5,
            txHash = "0x" + "a".repeat(64),
        )

        val result = heuristic.evaluate(SAMPLE_TOKEN)

        assertThat(result.evidence["lpClassification"]).isEqualTo(expectedClassification)
        assertThat(result.score).isBetween(scoreMin, scoreMax)
        assertThat(result.evidence["lpOwner"]).isEqualTo(owner)
    }

    @Test
    fun `no Mint event in the window returns low-confidence neutral`() = runTest {
        every { reader.fetchFirstMint(any(), any(), any()) } returns null

        val result = heuristic.evaluate(SAMPLE_TOKEN)

        assertThat(result.score).isEqualTo(0.5)
        assertThat(result.confidence).isEqualTo(0.0)
        assertThat(result.evidence["reason"]).isEqualTo("no_mint_in_window")
    }

    @Test
    fun `inputs hash is stable when called twice with the same Mint`() = runTest {
        val mint = MintEvent(
            sender = SAMPLE_TOKEN.deployerAddress,
            owner = SAMPLE_TOKEN.deployerAddress,
            amount0 = BigInteger.valueOf(1_000_000),
            amount1 = BigInteger.valueOf(1_000_000),
            blockNumber = SAMPLE_TOKEN.blockNumber + 1,
            txHash = "0xstable",
        )
        every { reader.fetchFirstMint(any(), any(), any()) } returns mint

        val a = heuristic.evaluate(SAMPLE_TOKEN)
        val b = heuristic.evaluate(SAMPLE_TOKEN)

        assertThat(a.inputsHash).isEqualTo(b.inputsHash)
    }

    companion object {
        @JvmStatic
        fun scenarios(): List<Array<Any>> {
            val csv = LpLockHeuristicTest::class.java
                .getResourceAsStream("/fixtures/lp-lock-fixtures.csv")
                ?.bufferedReader()?.readLines()
                ?: error("fixture missing")
            return csv.drop(1).map { line ->
                val c = line.split(",")
                arrayOf<Any>(
                    c[0],                      // scenario
                    c[5],                      // expected classification
                    c[6].toDouble(),           // score min
                    c[7].toDouble(),           // score max
                    c[1],                      // owner
                    c[2],                      // sender
                    BigInteger(c[3]),          // amount0
                    BigInteger(c[4]),          // amount1
                )
            }
        }

        val SAMPLE_TOKEN = TokenContext(
            tokenAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
            deployerAddress = "0x1111111111111111111111111111111111111111",
            poolAddress = "0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
            pairedWithAddress = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",
            feeTier = 3000,
            blockNumber = 22_500_000,
            blockHash = "0x" + "f".repeat(64),
            blockTimestamp = OffsetDateTime.parse("2026-05-14T00:00:00Z"),
            txHash = "0x" + "e".repeat(64),
        )
    }
}
