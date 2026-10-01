package banghak.stock.engine.adapter.`in`.ws

import banghak.stock.engine.adapter.`in`.web.filter.SessionAuth
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor

/**
 * 연결 요청은 REST 와 같은 필터(로컬 토큰·마법사 완료·세션)를 지나므로 여기서는 세션이 붙었는지만 확인함.
 * 세션 없이 연결하려 하면 거부함.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class LocalStreamHandshake : HandshakeInterceptor {
    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        val servletRequest = (request as? ServletServerHttpRequest)?.servletRequest
        val principal = servletRequest?.let {
            runCatching { SessionAuth.principalOf(it) }.getOrNull()
        }
        if (principal == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED)
            return false
        }
        attributes[USER_ID] = principal.userId.value
        return true
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) {}

    companion object {
        const val USER_ID = "userId"
    }
}
