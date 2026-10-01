package banghak.stock.engine.adapter.`in`.ws

import banghak.stock.core.domain.trading.StreamMessage
import banghak.stock.core.domain.trading.StreamViewerId
import banghak.stock.core.port.StreamPushPort
import banghak.stock.shared.config.RuntimeProfiles
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import tools.jackson.databind.json.JsonMapper

/**
 * 열린 로컬 WebSocket 연결 목록과 보내기.
 * 연결마다 보내기 전용 가상 스레드를 둬 한 연결의 느린 쓰기가 다른 화면과 다음 묶음을 막지 않게 함.
 * 밀린 묶음이 [MAX_PENDING] 을 넘는 느린 연결은 끊음(화면이 다시 붙어 최신값부터 받음).
 * 요청 처리([LocalStreamHandler])와 떼어 두어 스트림 서비스와 서로 의존하지 않게 함.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class LocalStreamSessions(private val mapper: JsonMapper) : StreamPushPort {
    private class Outbox(val session: WebSocketSession) {
        val sender: ExecutorService =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("ws-send-", 0).factory())
        val pending = AtomicInteger()
    }

    private val outboxes = ConcurrentHashMap<String, Outbox>()

    fun add(session: WebSocketSession) {
        outboxes[session.id] = Outbox(session)
    }

    fun remove(session: WebSocketSession) {
        outboxes.remove(session.id)?.sender?.shutdownNow()
    }

    override fun push(viewer: StreamViewerId, messages: List<StreamMessage>) {
        val outbox = outboxes[viewer.value] ?: return
        val text = mapper.writeValueAsString(messages.map(StreamServerMessage::of))
        if (outbox.pending.incrementAndGet() > MAX_PENDING) {
            outbox.pending.decrementAndGet()
            dropSlow(outbox)
            return
        }
        outbox.sender.execute {
            try {
                if (outbox.session.isOpen) outbox.session.sendMessage(TextMessage(text))
            } catch (e: IOException) {
                log.debug("닫히는 연결에 보내지 못함({})", e::class.simpleName)
            } finally {
                outbox.pending.decrementAndGet()
            }
        }
    }

    // 닫기도 막힐 수 있어 미는 쪽 스레드에서 하지 않음
    private fun dropSlow(outbox: Outbox) {
        if (outboxes.remove(outbox.session.id) == null) return
        log.warn("화면 연결 하나가 시세를 받아 가지 못해 끊음(밀린 묶음 {}개 초과)", MAX_PENDING)
        outbox.sender.shutdownNow()
        Thread.ofVirtual().start {
            runCatching { outbox.session.close(CloseStatus.SERVICE_OVERLOAD) }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(LocalStreamSessions::class.java)

        // 250ms 묶음 8개 = 약 2초 동안 받아 가지 못하면 느린 연결로 봄
        private const val MAX_PENDING = 8
    }
}
