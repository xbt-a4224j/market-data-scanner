package io.github.xbta4224j.scanner.chain

import io.reactivex.Flowable
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.response.Log

/**
 * Live subscription to the Uniswap V3 factory's PoolCreated stream via web3j
 * WebSocket. Each emitted log is decoded by [PoolCreatedDecoder] and enriched
 * with the token's deployer EOA via [EtherscanClient].
 *
 * Web3j's WebSocket transport is optional - it is conditional on the WSS URL
 * being configured. If no WebSocket bean exists (for example in tests, or in
 * environments where only an HTTP RPC is set), [subscribe] returns an empty
 * Flow rather than throwing - the rest of the pipeline (BackfillBlockSource,
 * heuristics, persistence) keeps working.
 */
@Component
class LiveBlockSource(
    private val wsWeb3jProvider: ObjectProvider<Web3j>,
    private val decoder: PoolCreatedDecoder,
    private val etherscan: EtherscanClient,
) : BlockSource {

    private val log = LoggerFactory.getLogger(javaClass)

    override val mode: BlockSource.Mode = BlockSource.Mode.LIVE

    override fun subscribe(): Flow<TokenContext> {
        val wsWeb3j = wsWeb3jProvider.ifAvailable
        if (wsWeb3j == null) {
            log.warn("no WebSocket web3j bean available; LiveBlockSource yields an empty Flow")
            return flowOf()
        }

        val filter = EthFilter(
            DefaultBlockParameterName.LATEST,
            DefaultBlockParameterName.LATEST,
            PoolCreatedDecoder.UNISWAP_V3_FACTORY,
        ).addSingleTopic(PoolCreatedDecoder.POOL_CREATED_TOPIC)

        val flowable: Flowable<Log> = wsWeb3j.ethLogFlowable(filter)
        return flowable.asFlow().mapNotNull { rawLog ->
            decode(rawLog)
        }
    }

    /**
     * Visible for tests so a synthetic log can be put through the same decode +
     * enrich path the live subscription uses.
     */
    fun decode(rawLog: Log): TokenContext? {
        val ctx = decoder.decode(rawLog) ?: return null
        val deployer = etherscan.getContractCreation(ctx.tokenAddress)?.contractCreator ?: ""
        val enriched = ctx.copy(deployerAddress = deployer)
        log.info(
            "pool detected: token={} pool={} pairedWith={} fee={} block={} tx={} deployer={}",
            enriched.tokenAddress,
            enriched.poolAddress,
            enriched.pairedWithAddress,
            enriched.feeTier,
            enriched.blockNumber,
            enriched.txHash,
            enriched.deployerAddress.ifBlank { "<unresolved>" },
        )
        return enriched
    }
}
