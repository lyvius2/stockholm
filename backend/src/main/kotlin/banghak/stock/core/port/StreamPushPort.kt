package banghak.stock.core.port

import banghak.stock.core.domain.trading.StreamMessage
import banghak.stock.core.domain.trading.StreamViewerId

/**
 * 화면 연결에 실시간 메시지를 미는 포트.
 * 구현은 로컬 WebSocket 이며, 끊긴 연결에는 조용히 버림.
 */
interface StreamPushPort {
    fun push(viewer: StreamViewerId, messages: List<StreamMessage>)
}
