package io.github.xbta4224j.scanner.ingestion

import io.github.xbta4224j.scanner.decoding.PoolCreatedDecoder
import io.github.xbta4224j.scanner.decoding.TokenContext

import kotlinx.coroutines.flow.Flow

/**
 * Sealed contract for "where do new TokenContexts come from".
 *
 * Two implementations:
 *  - [LiveBlockSource]: a web3j WebSocket subscription to the Uniswap V3 factory.
 *  - BackfillBlockSource (Issue #8): an iterator over a historical block range.
 *
 * Both produce the same `Flow<TokenContext>`. The IngestionPipeline (Issue #8)
 * consumes either without knowing which it is - the same scoring, persistence,
 * and reorg handling apply to both modes.
 */
/**
 * Not `sealed` so test fixtures (and future implementations like a websocket-
 * replay source) can implement from outside the chain package.
 */
interface BlockSource {
    val mode: Mode
    fun subscribe(): Flow<TokenContext>

    enum class Mode { LIVE, BACKFILL }
}
