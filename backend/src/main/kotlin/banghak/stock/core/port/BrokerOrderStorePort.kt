package banghak.stock.core.port

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.trading.BrokerOrder
import banghak.stock.core.domain.trading.BrokerOrderRecord
import banghak.stock.core.domain.trading.FillSummary
import banghak.stock.core.domain.trading.OrderListing
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.RecordedOrder
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

    fun findRecorded(userId: UserId, brokerOrderId: String): RecordedOrder?

    /**
     * 로컬에 기록된 출처.
     * 기록이 없으면 null(밖에서 낸 주문은 수동으로 기록됨).
     */
    fun findOrigin(userId: UserId, brokerOrderId: String): OrderOrigin?

    /**
     * 증권사가 알려 준 주문 상태·체결을 반영함.
     * 처음 보는 주문(토스 앱 등 밖에서 낸 주문)은 외부 주문으로 새로 기록하고, 우리 주문의 의도·출처는 건드리지 않음.
     */
    fun applyBrokerRecord(userId: UserId, record: BrokerOrderRecord, at: Instant)

    /** 이 주문에서 체결 대기열에 넣은 누적 요약을 남김(같은 트랜잭션에서 대기열에 넣은 뒤). */
    fun markFillQueued(userId: UserId, brokerOrderId: String, queued: FillSummary)

    /** 로컬에 아직 열린 상태로 남아 있는 주문 번호. */
    fun openBrokerOrderIds(userId: UserId): Set<String>

    /**
     * 열린 주문 목록(화면용).
     * 토스 앱에서 낸 주문도 포함.
     */
    fun findOpen(userId: UserId): List<OrderListing>

    /** [since] 이후에 갱신된 닫힌 주문 목록(화면용). */
    fun findClosedSince(userId: UserId, since: Instant): List<OrderListing>
}
