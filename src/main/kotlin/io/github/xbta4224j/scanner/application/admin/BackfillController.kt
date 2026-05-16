package io.github.xbta4224j.scanner.application.admin

import io.github.xbta4224j.scanner.ingestion.BackfillBlockSource
import io.github.xbta4224j.scanner.ingestion.EtherscanClient
import io.github.xbta4224j.scanner.decoding.PoolCreatedDecoder
import io.github.xbta4224j.scanner.ingestion.DateToBlockResolver
import io.github.xbta4224j.scanner.ingestion.IngestionPipeline
import io.github.xbta4224j.scanner.ingestion.WatermarkService
import io.github.xbta4224j.scanner.indexing.IngestionRunRepository
import io.github.xbta4224j.scanner.indexing.IngestionRunStatus
import io.github.xbta4224j.scanner.indexing.ProcessedBlockRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.web3j.protocol.Web3j
import java.time.Instant

/**
 * Admin backfill trigger + ingestion-run history.
 *
 * Three input modes (only one of: dates, blocks, or lookbackDays):
 *   - fromDate=...&toDate=...           ISO instants, resolved to blocks via Etherscan
 *   - fromBlock=...&toBlock=...         explicit block range
 *   - lookbackDays=N                    sliding window: now back N days
 *
 * Spawns the BackfillBlockSource in a SupervisorJob coroutine scope so a
 * single run failing does not take down the controller. The run is fire-
 * and-forget from the HTTP perspective; the caller polls /admin/runs to
 * watch progress.
 */
@Controller
class BackfillController(
    @Qualifier("web3jHttp") private val web3j: Web3j,
    private val decoder: PoolCreatedDecoder,
    private val etherscan: EtherscanClient,
    private val processedBlocks: ProcessedBlockRepository,
    private val pipeline: IngestionPipeline,
    private val resolver: DateToBlockResolver,
    private val ingestionRuns: IngestionRunRepository,
    private val watermark: WatermarkService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @GetMapping("/admin/backfill")
    fun page(model: Model): String {
        model.addAttribute("watermark", mapOf(
            "earliest" to (watermark.getEarliestProcessed()?.toString() ?: "—"),
            "latest"   to (watermark.getLatestProcessed()?.toString() ?: "—"),
        ))
        return "admin/backfill"
    }

    @PostMapping("/admin/backfill")
    fun trigger(
        @RequestParam(required = false) fromBlock: Long?,
        @RequestParam(required = false) toBlock: Long?,
        @RequestParam(required = false) fromDate: String?,
        @RequestParam(required = false) toDate: String?,
        @RequestParam(required = false) lookbackDays: Int?,
    ): String {
        val (from, to) = resolveRange(fromBlock, toBlock, fromDate, toDate, lookbackDays)
        log.info("admin backfill trigger: blocks {}..{}", from, to)
        val source = BackfillBlockSource(
            web3j = web3j,
            decoder = decoder,
            etherscan = etherscan,
            processedBlocks = processedBlocks,
            fromBlock = from,
            toBlock = to,
        )
        scope.launch {
            runCatching { pipeline.run(source, fromBlock = from, toBlock = to) }
                .onFailure { log.error("backfill {}..{} crashed", from, to, it) }
        }
        return "redirect:/admin/runs"
    }

    @GetMapping("/admin/runs")
    fun runs(model: Model): String {
        val running = ingestionRuns.findRunningRuns()
        val recent = ingestionRuns.findTop20ByOrderByStartedAtDesc()
        model.addAttribute("running", running)
        model.addAttribute("recent", recent)
        model.addAttribute("watermark", mapOf(
            "earliest" to (watermark.getEarliestProcessed()?.toString() ?: "—"),
            "latest"   to (watermark.getLatestProcessed()?.toString() ?: "—"),
        ))
        return "admin/runs"
    }

    private fun resolveRange(
        fromBlock: Long?, toBlock: Long?,
        fromDate: String?, toDate: String?,
        lookbackDays: Int?,
    ): Pair<Long, Long> {
        if (fromBlock != null && toBlock != null) {
            require(fromBlock <= toBlock) { "fromBlock $fromBlock > toBlock $toBlock" }
            return fromBlock to toBlock
        }
        if (lookbackDays != null && lookbackDays > 0) {
            val now = Instant.now()
            val past = now.minusSeconds(lookbackDays.toLong() * 86_400)
            val from = resolver.resolve(past) ?: error("could not resolve lookback start to a block")
            val to = resolver.resolve(now) ?: error("could not resolve now to a block")
            return from to to
        }
        if (fromDate != null && toDate != null) {
            val from = resolver.resolve(Instant.parse(fromDate)) ?: error("could not resolve fromDate")
            val to = resolver.resolve(Instant.parse(toDate)) ?: error("could not resolve toDate")
            return from to to
        }
        error("must provide either (fromBlock,toBlock), (fromDate,toDate), or lookbackDays")
    }
}
