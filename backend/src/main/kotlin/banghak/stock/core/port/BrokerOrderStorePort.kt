package banghak.stock.core.port

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.trading.BrokerOrder
import java.time.Instant

/**
 * Stockholm 이 낸 주문의 로컬 기록(`broker_order`).
 * 이벤트 로그와 같은 트랜잭션에서 갱신하는 projection 임.
 * 모든 조회는 사용자 범위 안에서만 동작함.
 */
interface BrokerOrderStorePort {
    /**
     * 접수된 주문을 기록함.
     * 같은 증권사 주문 번호가 이미 있으면(실시간 이벤트가 먼저 옴) 주문 의도·출처만 채움.
     */
    fun recordAccepted(order: BrokerOrder, isHighValueConfirmed: Boolean)

    /** [since] 이후에 낸 이 사용자·시장의 주문(체결·취소 포함). */
    fun findPlacedSince(userId: UserId, market: Market, since: Instant): List<BrokerOrder>
}
