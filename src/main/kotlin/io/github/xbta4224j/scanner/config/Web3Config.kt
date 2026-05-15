package io.github.xbta4224j.scanner.config

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.protocol.websocket.WebSocketClient
import org.web3j.protocol.websocket.WebSocketService
import java.net.URI

/**
 * Wires Web3j against the configured Ethereum RPC.
 *
 * Two transports:
 *  - HttpService for one-shot JSON-RPC calls (eth_getLogs, eth_call,
 *    eth_getTransactionByHash) used by the heuristics and BackfillBlockSource.
 *  - WebSocketService for the live subscription used by LiveBlockSource.
 *
 * Both are conditional on `ethereum.rpc.http-url` and `.websocket-url` being
 * non-empty. If they are empty (no INFURA_API_KEY / no ETHEREUM_RPC_URL set),
 * the beans are not created and LiveBlockSource simply will not start.
 */
@Configuration
class Web3Config(
    @Value("\${ethereum.rpc.http-url}") private val httpUrl: String,
    @Value("\${ethereum.rpc.websocket-url}") private val websocketUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var openedWsService: WebSocketService? = null

    @Bean
    fun web3jHttp(): Web3j {
        log.info("wiring web3j HTTP transport against {}", httpUrl.maskKey())
        return Web3j.build(HttpService(httpUrl))
    }

    /**
     * @Lazy: do not open the WebSocket connection at context load - that would
     * be a network call against the configured RPC URL and we want context load
     * to stay hermetic. The connection happens the first time something injects
     * the bean, which today is LiveBlockSource.subscribe() in production.
     */
    @Bean
    @Lazy
    @ConditionalOnProperty(prefix = "ethereum.rpc", name = ["websocket-url"], matchIfMissing = false)
    fun web3jWebSocketService(): WebSocketService {
        log.info("opening web3j WebSocket transport against {}", websocketUrl.maskKey())
        val ws = WebSocketService(WebSocketClient(URI.create(websocketUrl)), false)
        runCatching { ws.connect() }
            .onFailure { log.warn("WebSocket connect failed; LiveBlockSource will be inactive: {}", it.message) }
        openedWsService = ws
        return ws
    }

    @Bean
    @Lazy
    @ConditionalOnProperty(prefix = "ethereum.rpc", name = ["websocket-url"], matchIfMissing = false)
    fun web3jWebSocket(wsService: WebSocketService): Web3j = Web3j.build(wsService)

    @PreDestroy
    fun closeWs() {
        openedWsService?.close()
    }

    private fun String.maskKey(): String =
        // wss://mainnet.infura.io/ws/v3/<KEY> -> wss://mainnet.infura.io/ws/v3/****
        replace(Regex("/v[23]/[A-Za-z0-9]+"), "/v?/****")
}
