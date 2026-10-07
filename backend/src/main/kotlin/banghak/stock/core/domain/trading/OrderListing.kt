package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import java.time.Instant

/**
 * 화면 목록용 주문 한 줄(3번 영역 미체결·오늘 체결 탭).
 * 로컬 기록(`broker_order`)의 사실만 담고 의도·트리거는 복원하지 않음.
 * 토스 앱에서 낸 주문도 들어오며 그때 [isPlacedByStockholm] 은 false.
 */
data class OrderListing(
    val brokerOrderId: String,
    val replacesBrokerOrderId: String?,
    val symbol: Symbol,
    val side: OrderSide,
    val kind: OrderKind,
    val timeInForce: TimeInForce,
    val limitPrice: Money?,
    val quantity: Quantity?,
    val orderAmount: Money?,
    val status: OrderStatus,
    val filledQuantity: Quantity,
    val averageFilledPrice: Money?,
    val filledAmount: Money?,
    val origin: OrderOrigin,
    val isPlacedByStockholm: Boolean,
    val orderedAt: Instant,
    val updatedAt: Instant,
    /**
     * 체결·취소로 닫힌 시각(증권사 기준).
     * 거부·정정됨처럼 시각이 없으면 null.
     */
    val closedAt: Instant?,
) {
    init {
        if (brokerOrderId.isBlank()) throw InvalidValueException("주문 번호가 비어 있음")
    }

    /**
     * 잔량.
     * 금액 주문(수량 없음)은 0.
     */
    val remaining: Quantity
        get() = quantity?.minus(filledQuantity) ?: Quantity.ZERO

    /** 취소할 수 있는지(열려 있고 처리 중이 아님). */
    val canCancel: Boolean
        get() = status.isOpen && !status.isInFlight

    /**
     * 정정할 수 있는지.
     * 지정가만(시장가는 데몬도 거부함).
     */
    val canAmend: Boolean
        get() = canCancel && kind == OrderKind.LIMIT

    /**
     * 오늘 목록 분류·정렬에 쓰는 종료 시각.
     * 증권사 시각이 없으면 수집 시각.
     */
    val closedOrUpdatedAt: Instant
        get() = closedAt ?: updatedAt
}
