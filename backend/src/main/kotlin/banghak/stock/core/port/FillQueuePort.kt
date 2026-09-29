package banghak.stock.core.port

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.portfolio.FillState
import banghak.stock.core.domain.portfolio.QueuedFill
import banghak.stock.core.domain.trading.FillIncrement
import java.time.Instant

/**
 * 체결 대기열.
 * 모든 조회는 사용자 범위 안에서만 동작함.
 */
interface FillQueuePort {
    fun enqueue(fill: FillIncrement, at: Instant)

    /**
     * 아직 반영하지 않았거나 막힌 체결.
     * 체결 시각 순서임.
     */
    fun unprocessed(userId: UserId): List<QueuedFill>

    fun mark(userId: UserId, fillId: String, state: FillState, reason: String?, at: Instant)
}
