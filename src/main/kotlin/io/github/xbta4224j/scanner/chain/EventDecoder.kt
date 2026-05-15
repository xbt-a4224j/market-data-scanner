package io.github.xbta4224j.scanner.chain

import org.web3j.protocol.core.methods.response.Log

/**
 * Layer 2 of the canonical blockchain-analytics pipeline (see ADR-006):
 * normalise + decode a raw log into a typed domain event.
 *
 * One implementation per (factory contract, event signature) pair. The
 * `IngestionPipeline` does not need to know which factory it is listening to;
 * it consumes the typed `T` that the decoder produces. Adding a Uniswap V4
 * factory, a SushiSwap V3 factory, or a Curve pool factory is one new
 * `EventDecoder<TokenContext>` class — no other layer changes.
 *
 * Implementations must be stateless and safe to call from any thread; one
 * decoder bean is shared across the live WSS subscription and the backfill
 * iterator.
 */
interface EventDecoder<T> {
    /** Keccak-256 hash of the event signature; matches `log.topics[0]`. */
    val topic: String

    /**
     * Lower-cased mainnet address of the contract that emits this event.
     * Used to scope the `eth_subscribe` / `eth_getLogs` filter so we don't
     * decode logs from contracts we don't care about.
     */
    val sourceContract: String

    /**
     * Decode `rawLog` into a `T`, or return `null` if the log isn't relevant
     * (wrong topic, wrong topic count, both sides of pair already well-known,
     * etc.). Returning `null` is a normal control-flow signal, not an error.
     *
     * `blockTimestampSeconds` is supplied by the source when known (live mode
     * caches it from the latest header; backfill mode looks it up). When
     * `null`, the decoder falls back to wall-clock time.
     */
    fun decode(rawLog: Log, blockTimestampSeconds: Long? = null): T?
}
