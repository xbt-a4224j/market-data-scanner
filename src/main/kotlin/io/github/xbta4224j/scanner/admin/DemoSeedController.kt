package io.github.xbta4224j.scanner.admin

import io.github.xbta4224j.scanner.persistence.IngestionRun
import io.github.xbta4224j.scanner.persistence.IngestionRunMode
import io.github.xbta4224j.scanner.persistence.IngestionRunRepository
import io.github.xbta4224j.scanner.persistence.IngestionRunStatus
import io.github.xbta4224j.scanner.persistence.PoolDetection
import io.github.xbta4224j.scanner.persistence.PoolDetectionRepository
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import java.time.OffsetDateTime
import kotlin.random.Random

/**
 * Demo helper: POST /admin/demo-seed walks the curated scam + legit seed CSVs
 * and inserts them as synthetic pool_detections so the dashboard has something
 * to show before live mainnet detections start flowing in. Idempotent - if
 * detections already exist, the endpoint short-circuits.
 *
 * Strictly for demo / new-deploy bring-up; live ingestion writes "real"
 * detections through the IngestionPipeline.
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
        seeded += seedFromCsv("eval/known-scam-tokens.csv", run.id!!, isLegit = false)
        seeded += seedFromCsv("eval/known-legit-tokens.csv", run.id!!, isLegit = true)

        run.poolsDetected = seeded
        ingestionRuns.save(run)
        log.info("demo-seed inserted {} detections", seeded)
        return "redirect:/?demo-seed=ok"
    }

    private fun seedFromCsv(path: String, runId: Long, isLegit: Boolean): Int {
        val resource = ClassPathResource(path)
        val lines = resource.inputStream.bufferedReader().readLines().drop(1)
        var count = 0
        val now = OffsetDateTime.now()

        // Sample 25 rows from each side to keep the demo dataset visible but not overwhelming
        for ((i, line) in lines.shuffled(random).take(25).withIndex()) {
            val cols = line.split(",")
            val tokenAddr = cols[0]
            val deployerCol = cols.first { it.startsWith("0x") && it != tokenAddr }
            val blockCol = cols.first { it.toLongOrNull() != null }

            val composite = if (isLegit) random.nextInt(0, 35) else random.nextInt(60, 96)
            val flagged = if (composite >= 70) listOf("deployer_history", "supply_concentration") else emptyList()

            poolDetections.save(PoolDetection(
                txHash = "0x" + "ab".repeat(32),
                poolAddress = "0x" + (i.toString(16).padStart(40, '0')) + tokenAddr.takeLast(2),
                tokenAddress = tokenAddr,
                deployerAddress = deployerCol,
                pairedWith = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",
                feeTier = listOf(500, 3000, 10000).random(random),
                blockNumber = blockCol.toLong(),
                blockHash = "0x" + (i.toString(16).padStart(64, '0')),
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
            count++
        }
        val flavor = if (isLegit) "legit" else "scam"
        log.info("seeded {} {} entries", count, flavor)
        return count
    }
}
