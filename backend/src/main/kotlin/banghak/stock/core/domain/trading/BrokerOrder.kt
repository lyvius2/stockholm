package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.money.Money
import java.time.Instant

/**
 * 증권사에 접수된 주문.
 * 정정·취소는 토스가 새 주문 번호를 발급하므로 [replacesBrokerOrderId] 로 원주문 → 새 주문 체인을 이음.
 * 정정·취소 가능 여부는 [OrderStatus.isChangeable] 로 판정하고, 정정 내용 검사는 증권사
 * 기록([BrokerOrderRecord.requireAmendable])에서 함.
 */
data class BrokerOrder(
    val clientOrderId: ClientOrderId,
    val brokerOrderId: String,
    val replacesBrokerOrderId: String?,
    val intent: OrderIntent,
    val status: OrderStatus,
    val filledQuantity: Quantity,
    val averageFilledPrice: Money?,
    val updatedAt: Instant,
) {
    init {
        if (brokerOrderId.isBlank()) throw InvalidValueException("brokerOrderId 가 비어 있음")
        val ordered = intent.quantity
        if (ordered != null && filledQuantity.isGreaterThan(ordered))
            throw InvalidValueException("체결 수량 $filledQuantity 이 주문 수량 $ordered 보다 큼")
    }

    /**
     * 잔량.
     * 금액 주문(수량 없음)은 잔량 개념이 없어 0 임.
     */
    fun remaining(): Quantity = intent.quantity?.minus(filledQuantity) ?: Quantity.ZERO

    val isOpen: Boolean
        get() = status.isOpen

    /**
     * 처리 중(취소·정정 대기)이 아닌 열린 주문만.
     * 출처와 무관하게 자동 주문도 사람이 정정할 수 있음.
     */
    val canAmend: Boolean
        get() = status.isChangeable

    val canCancel: Boolean
        get() = status.isChangeable
}
