package io.github.xbta4224j.scanner.persistence

import io.github.xbta4224j.scanner.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@SpringBootTest
@Transactional
class PoolDetectionRepositoryTest @Autowired constructor(
    private val pools: PoolDetectionRepository,
    private val runs: IngestionRunRepository,
) : PostgresIntegrationTest() {

    private var runId: Long = 0

    @BeforeEach
    fun seedRun() {
        val run = runs.save(
            IngestionRun(
                mode = IngestionRunMode.LIVE,
                fromBlock = 22_500_000,
                status = IngestionRunStatus.RUNNING,
            )
        )
        runId = run.id!!
    }

    @Test
    fun `pool detection round-trips JSONB and array fields through Postgres`() {
        val saved = pools.save(
            samplePool(
                txHash = "0x" + "1".repeat(64),
                pool = "0x" + "a".repeat(40),
                token = "0x" + "b".repeat(40),
                deployer = "0x" + "c".repeat(40),
                score = 87,
                versions = mapOf("supply_concentration" to "1.0.0", "lp_lock" to "1.0.0"),
                results = mapOf(
                    "supply_concentration" to mapOf("top1Pct" to 0.94, "gini" to 0.88),
                    "lp_lock" to mapOf("destination" to "deployer", "initialUsd" to 1200.0),
                ),
                signals = listOf("supply_concentrated", "deployer_held_lp"),
            )
        )
        pools.flush()

        val reloaded = pools.findById(saved.id!!).orElseThrow()
        assertThat(reloaded.compositeScore).isEqualTo(87)
        assertThat(reloaded.heuristicVersions).containsEntry("supply_concentration", "1.0.0")
        @Suppress("UNCHECKED_CAST")
        val supply = reloaded.heuristicResults["supply_concentration"] as Map<String, Any>
        assertThat(supply["top1Pct"]).isEqualTo(0.94)
        assertThat(reloaded.flaggedSignals).containsExactlyInAnyOrder(
            "supply_concentrated", "deployer_held_lp"
        )
    }

    @Test
    fun `findTop50ByOrderByDetectedAtDesc returns most recent first`() {
        val baseTime = OffsetDateTime.parse("2026-05-14T00:00:00Z")
        repeat(5) { i ->
            pools.save(
                samplePool(
                    txHash = "0x" + i.toString().repeat(64).take(64),
                    pool = "0x" + ('a' + i).toString().repeat(40),
                    token = "0x" + ('b' + i).toString().repeat(40),
                    deployer = "0x" + ('c' + i).toString().repeat(40),
                    detectedAt = baseTime.plusMinutes(i.toLong()),
                )
            )
        }
        pools.flush()

        val top = pools.findTop50ByOrderByDetectedAtDesc()
        assertThat(top).hasSize(5)
        assertThat(top.map { it.detectedAt }).isSortedAccordingTo(compareByDescending { it })
    }

    @Test
    fun `countFlaggedSince counts only detections above the score floor since the cutoff`() {
        val now = OffsetDateTime.now()
        pools.save(samplePool(score = 30, detectedAt = now.minusMinutes(5)))
        pools.save(samplePool(score = 80, detectedAt = now.minusMinutes(5)))
        pools.save(samplePool(score = 95, detectedAt = now.minusMinutes(5)))
        pools.save(samplePool(score = 95, detectedAt = now.minusHours(2)))  // outside cutoff
        pools.flush()

        val count = pools.countFlaggedSince(since = now.minusMinutes(30), scoreFloor = 70)
        assertThat(count).isEqualTo(2)
    }

    private var counter = 0
    private fun samplePool(
        txHash: String = "0x" + "f".repeat(64),
        pool: String = uniqueAddress(),
        token: String = uniqueAddress(),
        deployer: String = uniqueAddress(),
        score: Int = 50,
        versions: Map<String, String> = mapOf("supply_concentration" to "1.0.0"),
        results: Map<String, Any> = mapOf("supply_concentration" to mapOf("top1Pct" to 0.5)),
        signals: List<String> = emptyList(),
        detectedAt: OffsetDateTime = OffsetDateTime.now(),
    ) = PoolDetection(
        txHash = txHash,
        poolAddress = pool,
        tokenAddress = token,
        deployerAddress = deployer,
        pairedWith = "0x" + "0".repeat(40),
        feeTier = 3000,
        blockNumber = 22_500_000L + counter,
        blockHash = uniqueHash(),
        blockTimestamp = OffsetDateTime.now(),
        detectedAt = detectedAt,
        ingestionRunId = runId,
        compositeScore = score,
        heuristicVersions = versions,
        heuristicResults = results,
        flaggedSignals = signals,
    )

    private fun uniqueAddress(): String {
        counter += 1
        return "0x" + counter.toString(16).padStart(40, '0')
    }

    private fun uniqueHash(): String {
        counter += 1
        return "0x" + counter.toString(16).padStart(64, '0')
    }
}
