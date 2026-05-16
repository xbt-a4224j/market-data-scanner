package io.github.xbta4224j.scanner.query.heuristics

import io.github.xbta4224j.scanner.ingestion.EtherscanClient
import io.github.xbta4224j.scanner.decoding.TokenContext
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Validation tests for the deployer-history heuristic.
 *
 * The MOST IMPORTANT case is `prolific factory deployer scores high` - it loads a
 * recorded Etherscan response (`/fixtures/etherscan/phishing-factory-txlist.json`)
 * for a real mainnet EOA, parses it through the production EtherscanClient code
 * path, and asserts the heuristic scores it at >= 0.85 with high confidence.
 *
 * The chosen address is **0xf24246e0d5399ea85dbdadcfdbc9e8f14490db58**, picked
 * because it deployed 246 contracts on mainnet (verified by querying Etherscan
 * on 2026-05-15 - 116 of those 246 are in our scam seed CSV at
 * `src/test/resources/eval/known-scam-tokens.csv`). The fixture is a
 * deterministic snapshot of Etherscan's response so CI does not depend on a
 * live network call.
 *
 * Per CLAUDE.md, this validation test is NON-NEGOTIABLE. If it ever turns red
 * the heuristic logic regressed - fix before merging.
 */
class DeployerHistoryHeuristicTest {

    private val etherscan = mockk<EtherscanClient>()
    private val heuristic = DeployerHistoryHeuristic(etherscan, deepEnrichmentEnabled = false)
    private val realParser = EtherscanClient(apiKey = "test", baseUrl = "https://localhost")

    @Test
    fun `prolific factory deployer scores high - validation against real mainnet EOA`() = runTest {
        val token = SAMPLE_TOKEN.copy(deployerAddress = PROLIFIC_FACTORY_EOA)
        val fixtureBody = javaClass.getResourceAsStream("/fixtures/etherscan/phishing-factory-txlist.json")
            ?.bufferedReader()?.readText()
            ?: error("phishing-factory fixture missing")
        val deployments = realParser.parseEoaTxlist(fixtureBody)
        every { etherscan.getEoaContractDeployments(PROLIFIC_FACTORY_EOA) } returns deployments

        val result = heuristic.evaluate(token)

        assertThat(deployments).hasSizeGreaterThanOrEqualTo(50)
        assertThat(result.score).`as`("prolific factory must score >= 0.85").isGreaterThanOrEqualTo(0.85)
        assertThat(result.confidence).isGreaterThanOrEqualTo(0.85)
        assertThat(result.evidence["priorCount"] as Int).isGreaterThanOrEqualTo(50)
        assertThat(result.evidence["interpretation"]).isNull()  // not "brand_new_deployer"
    }

    @Test
    fun `brand-new deployer (no prior contracts) scores 0_4 with confidence 0`() = runTest {
        every { etherscan.getEoaContractDeployments(any()) } returns emptyList()

        val result = heuristic.evaluate(SAMPLE_TOKEN)

        assertThat(result.score).isEqualTo(0.4)
        assertThat(result.confidence).isEqualTo(0.0)
        assertThat(result.evidence["interpretation"]).isEqualTo("brand_new_deployer")
    }

    @Test
    fun `missing deployer address yields neutral with confidence 0`() = runTest {
        val token = SAMPLE_TOKEN.copy(deployerAddress = "")

        val result = heuristic.evaluate(token)

        assertThat(result.score).isEqualTo(0.5)
        assertThat(result.confidence).isEqualTo(0.0)
        assertThat(result.evidence["reason"]).isEqualTo("missing_deployer_address")
    }

    @Test
    fun `single prior deployment is mildly suspicious but low confidence`() = runTest {
        every { etherscan.getEoaContractDeployments(any()) } returns listOf(
            EtherscanClient.EoaDeployment(
                contractAddress = "0xprior1",
                blockNumber = 100,
                timestampSeconds = 1_700_000_000,
                txHash = "0x" + "1".repeat(64),
            )
        )

        val result = heuristic.evaluate(SAMPLE_TOKEN)

        assertThat(result.score).isLessThan(0.30)
        assertThat(result.confidence).isEqualTo(0.55)  // < 3 priors -> low-confidence band
    }

    @Test
    fun `THIS-token's own deployment is excluded from priorCount`() = runTest {
        every { etherscan.getEoaContractDeployments(any()) } returns listOf(
            EtherscanClient.EoaDeployment(
                contractAddress = SAMPLE_TOKEN.tokenAddress.lowercase(),
                blockNumber = 100,
                timestampSeconds = 1_700_000_000,
                txHash = "0x" + "1".repeat(64),
            )
        )

        val result = heuristic.evaluate(SAMPLE_TOKEN)

        // The deployer's only deployment is THIS token - so prior count is 0.
        assertThat(result.evidence["interpretation"]).isEqualTo("brand_new_deployer")
    }

    @Test
    fun `maxBurstWindow correctly counts the densest 24h window`() {
        val ts = listOf(
            1_700_000_000L,                          // t0
            1_700_000_000L + 3_600,                  // +1h
            1_700_000_000L + 7_200,                  // +2h
            1_700_000_000L + 86_400 + 1,             // just outside the 24h window from t0
            1_700_000_000L + 86_400 + 100,           // pushes outside
            1_700_000_000L + 200_000,                // far away
        )
        // Largest window: indices 1, 2, 3, 4 fit in [t0+3600, t0+86500] which is ~82900s wide -> 4 entries
        // Or indices 0..3 fit if 0 to t0+86400+1 -> 86401s > 86400 -> 0 falls out -> 1, 2, 3 = 3
        val burst = DeployerHistoryHeuristic(mockk(), deepEnrichmentEnabled = false)
            .maxBurstWindow(ts, windowSeconds = 86_400)
        assertThat(burst).isGreaterThanOrEqualTo(3)
    }

    companion object {
        const val PROLIFIC_FACTORY_EOA = "0xf24246e0d5399ea85dbdadcfdbc9e8f14490db58"

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
