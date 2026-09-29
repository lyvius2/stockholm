package banghak.stock.core.port

import banghak.stock.core.domain.identity.UserId
import java.time.Instant

/**
 * 사용자별 lot 원장 시작 시각.
 * 이 시각의 증권사 보유로 기초 lot 을 만들고, 이보다 앞선 체결은 lot 에 반영하지 않음.
 */
interface LotLedgerPort {
    fun startedAt(userId: UserId): Instant?

    fun start(userId: UserId, at: Instant)
}
