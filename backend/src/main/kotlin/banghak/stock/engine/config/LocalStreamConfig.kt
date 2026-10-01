package banghak.stock.engine.config

import banghak.stock.engine.adapter.`in`.ws.LocalStreamHandler
import banghak.stock.engine.adapter.`in`.ws.LocalStreamHandshake
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/**
 * 로컬 WebSocket 경로 `/ws` 하나.
 * 데몬은 127.0.0.1 에만 묶이고 로컬 토큰 필터가 앞에 있어 Origin 은 따로 가리지 않음(Electron main 은 Origin 을 보내지 않음).
 */
@Configuration
@Profile(RuntimeProfiles.ENGINE)
@EnableWebSocket
class LocalStreamConfig(
    private val handler: LocalStreamHandler,
    private val handshake: LocalStreamHandshake,
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, PATH).addInterceptors(handshake).setAllowedOrigins("*")
    }

    companion object {
        const val PATH = "/ws"
    }
}
