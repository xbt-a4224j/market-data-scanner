package io.github.xbta4224j.scanner.application.admin

import io.github.xbta4224j.scanner.indexing.IngestionRun
import io.github.xbta4224j.scanner.indexing.IngestionRunMode
import io.github.xbta4224j.scanner.indexing.IngestionRunRepository
import io.github.xbta4224j.scanner.indexing.IngestionRunStatus
import io.github.xbta4224j.scanner.indexing.PoolDetection
import io.github.xbta4224j.scanner.indexing.PoolDetectionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import java.time.OffsetDateTime
import kotlin.random.Random

/**
 * Demo helper: POST /admin/demo-seed inserts ~50 synthetic pool_detections so
 * the dashboard charts have something to render before live mainnet detections
 * start flowing. Idempotent - if detections already exist, the endpoint
 * short-circuits.
 *
 * Synthetic addresses are derived from a seeded RNG so re-runs after a clean
 * DB give the same data.
 */
@Controller
class DemoSeedController(
    private val poolDetections: PoolDetectionRepository,
    private val ingestionRuns: IngestionRunRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = Random(42)

    @PostMapping("/admin/demo-seed")
    fun seed(): String {
        val existing = poolDetections.count()
        if (existing > 0) {
            log.info("demo-seed skipped: {} pool_detections already present", existing)
            return "redirect:/?demo-seed=skipped"
        }

        val run = ingestionRuns.save(IngestionRun(
            mode = IngestionRunMode.BACKFILL,
            fromBlock = 0,
            toBlock = 0,
            status = IngestionRunStatus.COMPLETED,
            startedAt = OffsetDateTime.now().minusMinutes(5),
            endedAt = OffsetDateTime.now(),
            createdBy = "demo-seed",
        ))

        var seeded = 0
        seeded += seedSynthetic(run.id!!, isLegit = false, count = 25)
        seeded += seedSynthetic(run.id!!, isLegit = true, count = 25)

        run.poolsDetected = seeded
        ingestionRuns.save(run)
        log.info("demo-seed inserted {} detections", seeded)
        return "redirect:/?demo-seed=ok"
    }

    /**
     * Generates [count] synthetic pool_detections with a composite score
     * distribution shaped by the [isLegit] flag (legit ~ U[0,35], scam ~
     * U[60,96]). Pool / token / deployer addresses are derived from the
     * RNG so re-runs are deterministic.
     */
    private fun seedSynthetic(runId: Long, isLegit: Boolean, count: Int): Int {
        val now = OffsetDateTime.now()
        val flavor = if (isLegit) "legit" else "scam"
        var seeded = 0
        repeat(count) { i ->
            val composite = if (isLegit) random.nextInt(0, 35) else random.nextInt(60, 96)
            val flagged = if (composite >= 70) listOf("deployer_history", "supply_concentration") else emptyList()

            val tokenAddr = "0x" + hex40("$flavor-token-$i")
            val deployerAddr = "0x" + hex40("$flavor-dep-${i % 3}")
            val poolAddr = "0x" + hex40("$flavor-pool-$i")
            val blockNum = 22_500_000L + i * 17 + (if (isLegit) 0 else 100_000)

            poolDetections.save(PoolDetection(
                txHash = "0x" + hex64("$flavor-tx-$i"),
                poolAddress = poolAddr,
                tokenAddress = tokenAddr,
                deployerAddress = deployerAddr,
                pairedWith = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",
                feeTier = listOf(500, 3000, 10000).random(random),
                blockNumber = blockNum,
                blockHash = "0x" + hex64("$flavor-block-$i"),
                blockTimestamp = now.minusMinutes(random.nextLong(0, 240)),
                detectedAt = now.minusMinutes(random.nextLong(0, 60)),
                ingestionRunId = runId,
                compositeScore = composite,
                heuristicVersions = mapOf(
                    "supply_concentration" to "1.0.0",
                    "lp_lock" to "1.0.0",
                    "deployer_history" to "1.0.0",
                ),
                heuristicResults = mapOf(
                    "deployer_history" to mapOf(
                        "score" to (composite / 100.0),
                        "confidence" to 0.85,
                        "evidence" to mapOf("priorCount" to (if (isLegit) 1 else random.nextInt(20, 200))),
                        "stubbed" to false,
                    ),
                ),
                flaggedSignals = flagged,
            ))
            seeded++
        }
        log.info("seeded {} {} entries", seeded, flavor)
        return seeded
    }

    private fun hex40(seed: String): String =
        seed.hashCode().toUInt().toString(16).repeat(6).take(40)

    private fun hex64(seed: String): String =
        seed.hashCode().toUInt().toString(16).repeat(10).take(64)
}
