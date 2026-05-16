package io.github.xbta4224j.scanner.ingestion

import io.github.xbta4224j.scanner.decoding.PoolCreatedDecoder
import io.github.xbta4224j.scanner.decoding.TokenContext

import io.github.xbta4224j.scanner.indexing.ProcessedBlockRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.methods.request.EthFilter
import java.math.BigInteger

/**
 * Iterates a historical block range and emits a `TokenContext` for every
 * Uniswap V3 PoolCreated event in that window. Skips blocks the ledger
 * already marked processed (via [ProcessedBlockRepository.existsByBlockNumberAndBlockHash])
 * so a crashed backfill can be re-launched and pick up where it stopped.
 *
 * Chunks the range into [chunkSize]-block windows so a single eth_getLogs call
 * stays under typical RPC limits (Alchemy / Infura cap at 10k logs per request;
 * 1000 blocks of factory events is well under that).
 *
 * Constructed per backfill run by [io.github.xbta4224j.scanner.application.admin.BackfillController]
 * (Issue #12). Not a Spring bean - it carries per-run state (the block range).
 */
class BackfillBlockSource(
    private val web3j: Web3j,
    private val decoder: PoolCreatedDecoder,
    private val etherscan: EtherscanClient,
    private val processedBlocks: ProcessedBlockRepository,
    val fromBlock: Long,
    val toBlock: Long,
    private val chunkSize: Long = 1000,
) : BlockSource {

    private val log = LoggerFactory.getLogger(javaClass)

    override val mode: BlockSource.Mode = BlockSource.Mode.BACKFILL

    private companion object {
        const val THROTTLE_MS = 250L
    }

    override fun subscribe(): Flow<TokenContext> = flow {
        require(fromBlock <= toBlock) { "fromBlock $fromBlock > toBlock $toBlock" }
        log.info("backfill starting: blocks {}..{} chunkSize={}", fromBlock, toBlock, chunkSize)

        var cursor = fromBlock
        var emitted = 0
        while (cursor <= toBlock) {
            val end = minOf(cursor + chunkSize - 1, toBlock)
            val filter = EthFilter(
                DefaultBlockParameter.valueOf(BigInteger.valueOf(cursor)),
                DefaultBlockParameter.valueOf(BigInteger.valueOf(end)),
                PoolCreatedDecoder.UNISWAP_V3_FACTORY,
            ).addSingleTopic(PoolCreatedDecoder.POOL_CREATED_TOPIC)

            // Inter-chunk pacing keeps Infura's per-second compute-units cap
            // out of trouble; each eth_getLogs over a 1k-block window is
            // ~26 CU, and the free tier allows 500 CU/s.
            Thread.sleep(THROTTLE_MS)
            val logs = runCatching { web3j.ethGetLogs(filter).send().logs }
                .onFailure { log.warn("eth_getLogs failed for {}..{}: {}", cursor, end, it.message) }
                .getOrDefault(emptyList())

            for (logResult in logs) {
                val l = (logResult.get() as? org.web3j.protocol.core.methods.response.Log) ?: continue
                if (processedBlocks.existsByBlockNumberAndBlockHash(l.blockNumber.toLong(), l.blockHash)) {
                    continue  // resumability: already done
                }
                val ctx = decoder.decode(l) ?: continue
                val deployer = etherscan.getContractCreation(ctx.tokenAddress)?.contractCreator ?: ""
                emit(ctx.copy(deployerAddress = deployer))
                emitted++
            }
            cursor = end + 1
        }
        log.info("backfill done: blocks {}..{} emitted {} contexts", fromBlock, toBlock, emitted)
    }
}
