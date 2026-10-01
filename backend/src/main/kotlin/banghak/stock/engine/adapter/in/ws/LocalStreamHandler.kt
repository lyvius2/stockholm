package banghak.stock.engine.adapter.`in`.ws

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.trading.StreamViewerId
import banghak.stock.core.domain.trading.StreamWatch
import banghak.stock.core.usecase.MarketStreamUseCase
import banghak.stock.shared.config.RuntimeProfiles
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.json.JsonMapper

/**
 * 로컬 WebSocket(`/ws`) 한 연결이 화면 하나임.
 * 화면은 보는 종목 전체를 선언하고, 데몬은 모아 둔 시세를 [LocalStreamSessions] 로 밈.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class LocalStreamHandler(
    private val stream: MarketStreamUseCase,
    private val sessions: LocalStreamSessions,
    private val mapper: JsonMapper,
) : TextWebSocketHandler() {
    override fun afterConnectionEstablished(session: WebSocketSession) = sessions.add(session)

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        try {
            val request = mapper.readValue(message.payload, StreamClientMessage::class.java)
            if (request.type != SUBSCRIBE) throw InvalidValueException("모르는 메시지: ${request.type}")
            stream.watch(
                StreamWatch(
                    StreamViewerId(session.id),
                    request.symbols.map { it.toSymbol() }.toSet(),
                )
            )
        } catch (e: RuntimeException) {
            log.warn("스트림 요청을 처리하지 못함({})", e::class.simpleName)
            session.close(CloseStatus.BAD_DATA)
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        sessions.remove(session)
        stream.leave(StreamViewerId(session.id))
    }

    companion object {
        private val log = LoggerFactory.getLogger(LocalStreamHandler::class.java)
        private const val SUBSCRIBE = "subscribe"
    }
}
