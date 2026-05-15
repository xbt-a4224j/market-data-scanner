package io.github.xbta4224j.scanner.chain

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.web3j.protocol.Web3j

/**
 * Resolves the actual end-holder of a Uniswap V3 LP position when the
 * pool's first Mint event was minted via the NonfungiblePositionManager.
 *
 * The NPM mints an ERC-721 NFT representing the position; the Mint event's
 * `owner` field is the NPM contract itself (`0xC36442b4a4522E871399CD717aBDD847Ab11FE88`).
 * To find who actually receives that NFT, we look at the transaction receipt's
 * logs and find the ERC-721 `Transfer(from=0x0, to=user, tokenId)` event
 * emitted by the NPM contract in the same tx (mint events have from=0x0).
 *
 * ERC-20 and ERC-721 share the same `Transfer(address,address,uint256)`
 * topic-0 hash, so we filter additionally on the log's emitter address
 * being the NPM contract.
 */
@Component
class NpmPositionTracer(
    @Qualifier("web3jHttp") private val web3j: Web3j,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Returns the actual LP-position-NFT recipient for [txHash] (lowercased,
     * 0x-prefixed) or null if no NPM mint Transfer is found in the receipt
     * (e.g. minted directly without going through NPM, or RPC failure).
     */
    fun traceNpmRecipient(txHash: String): String? = runCatching {
        val receipt = web3j.ethGetTransactionReceipt(txHash).send()
            .takeIf { !it.hasError() }
            ?.transactionReceipt?.orElse(null) ?: return null

        receipt.logs.firstOrNull { l ->
            l.address.equals(LockContracts.UNISWAP_V3_NPM, ignoreCase = true) &&
                l.topics.size == 4 &&
                l.topics[0].equals(TRANSFER_TOPIC, ignoreCase = true) &&
                isZeroTopic(l.topics[1])
        }?.let { decodeAddress(it.topics[2]) }
    }.onFailure { log.warn("NPM trace for tx {} failed", txHash, it) }
        .getOrNull()

    private fun isZeroTopic(topic: String): Boolean =
        topic.removePrefix("0x").trimStart('0').isEmpty()

    private fun decodeAddress(topic: String): String =
        ("0x" + topic.removePrefix("0x").takeLast(40)).lowercase()

    companion object {
        // keccak256("Transfer(address,address,uint256)") - shared by ERC-20 and ERC-721
        const val TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
    }
}
