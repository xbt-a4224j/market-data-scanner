package io.github.xbta4224j.scanner.analysis.heuristics

import io.github.xbta4224j.scanner.chain.EtherscanClient
import io.github.xbta4224j.scanner.chain.TokenContext
import io.github.xbta4224j.scanner.chain.Transfer
import io.github.xbta4224j.scanner.chain.TransferLogReader
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.time.OffsetDateTime

class SupplyConcentrationHeuristicTest {

    private val reader = mockk<TransferLogReader>()
    private val etherscan = mockk<EtherscanClient>()
    private val heuristic = SupplyConcentrationHeuristic(reader, etherscan)

    @Test
    fun `clean uniform distribution scores low and reports low gini`() = runTest {
        loadFixture("clean_uniform")

        val result = heuristic.evaluate(SAMPLE_TOKEN)

        assertThat(result.confidence).isEqualTo(0.85)
        assertThat(result.score).isLessThan(0.20)
        assertThat(result.evidence["top1Pct"] as Double).isEqualTo(0.10)
        assertThat(result.evidence["top3Pct"] as Double).isEqualTo(0.30)
        assertThat(result.evidence["totalHolders"] as Int).isEqualTo(10)
        assertThat(result.evidence["gini"] as Double).isLessThan(0.05)
    }

    @Test
    fun `rug pattern with 95pct in top-1 scores high`() = runTest {
        loadFixture("rug_pattern")

        val result = heuristic.evaluate(SAMPLE_TOKEN)

        assertThat(result.confidence).isEqualTo(0.85)
        assertThat(result.evidence["top1Pct"] as Double).isEqualTo(0.95)
        assertThat(result.evidence["top3Pct"] as Double).isEqualTo(1.00)
        // With only 3 holders the Gini ceiling is bounded; score lands in the
        // 0.80-0.90 band. With many small holders + one dominant (the pattern
        // CLAUDE.md cites at 0.99 top-1) the score climbs near 0.99 because
        // Gini drives strongly upward for that distribution.
        assertThat(result.score).isGreaterThan(0.80)
    }

    @Test
    fun `moderate distribution lands between clean and rug`() = runTest {
        loadFixture("moderate")

        val result = heuristic.evaluate(SAMPLE_TOKEN)

        // Top-3 = (5000+2000+1500)/10900 ~= 0.78 -> score ~0.78 * 0.6 + gini * 0.4 = ~0.65
        assertThat(result.score).isBetween(0.50, 0.80)
        assertThat(result.evidence["top1Pct"] as Double).isBetween(0.40, 0.50)
    }

    @Test
    fun `burn-chunk pattern is NOT flagged as concentrated - dead address excluded`() = runTest {
        loadFixture("burn_chunk")

        val result = heuristic.evaluate(SAMPLE_TOKEN)

        // 8000 sent to dead, then 4 holders of 500 each. Holder set sees only the 2000 of real circulation.
        assertThat(result.evidence["totalHolders"] as Int).isEqualTo(4)
        assertThat(result.evidence["top1Pct"] as Double).isEqualTo(0.25)  // 500 / 2000
        assertThat(result.score).isLessThan(0.50)
    }

    @Test
    fun `no transfers returns low-confidence neutral`() = runTest {
        every { etherscan.getContractCreation(any()) } returns null
        every { reader.fetchTransfers(any(), any(), any()) } returns emptyList()

        val result = heuristic.evaluate(SAMPLE_TOKEN)

        assertThat(result.score).isEqualTo(0.5)
        assertThat(result.confidence).isEqualTo(0.0)
        assertThat(result.evidence["reason"]).isEqualTo("no_transfers_found")
    }

    @Test
    fun `inputs hash is stable across runs with the same inputs`() = runTest {
        loadFixture("clean_uniform")

        val first = heuristic.evaluate(SAMPLE_TOKEN)
        val second = heuristic.evaluate(SAMPLE_TOKEN)

        assertThat(first.inputsHash).isEqualTo(second.inputsHash)
    }

    private fun loadFixture(scenario: String) {
        every { etherscan.getContractCreation(any()) } returns EtherscanClient.ContractCreation(
            contractAddress = SAMPLE_TOKEN.tokenAddress,
            contractCreator = SAMPLE_TOKEN.deployerAddress,
            txHash = "0x" + "f".repeat(64),
            blockNumber = 50,
            timestampSeconds = 1_700_000_000,
        )
        every { reader.fetchTransfers(any(), any(), any()) } returns readTransfers(scenario)
    }

    private fun readTransfers(scenario: String): List<Transfer> {
        val csv = javaClass.getResourceAsStream("/fixtures/supply-concentration-fixtures.csv")
            ?.bufferedReader()?.readLines()
            ?: error("fixtures CSV missing")
        return csv.drop(1).map { it.split(",") }
            .filter { it[0] == scenario }
            .map { cols ->
                Transfer(
                    from = cols[1],
                    to = cols[2],
                    value = BigInteger(cols[3]),
                    blockNumber = cols[4].toLong(),
                )
            }
    }

    companion object {
        val SAMPLE_TOKEN = TokenContext(
            tokenAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
            deployerAddress = "0x1111111111111111111111111111111111111111",
            poolAddress = "0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
            pairedWithAddress = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",
            feeTier = 3000,
            blockNumber = 200,
            blockHash = "0x" + "a".repeat(64),
            blockTimestamp = OffsetDateTime.parse("2026-05-14T00:00:00Z"),
            txHash = "0x" + "b".repeat(64),
        )
    }
}
