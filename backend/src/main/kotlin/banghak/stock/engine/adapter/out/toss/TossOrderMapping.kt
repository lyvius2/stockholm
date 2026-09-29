package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.BrokerOrderRecord
import banghak.stock.core.domain.trading.OrderKind
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.OrderStatus
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TimeInForce
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime

/** 토스 주문 응답(REST 상세·목록, 웹소켓 personal:order)을 도메인 주문 레코드로 바꿈. */
internal object TossOrderMapping {
    fun recordOf(order: TossOrder): BrokerOrderRecord {
        val currency = Currency.valueOf(order.currency)
        val money = { amount: BigDecimal? -> amount?.let { Money.of(it, currency) } }
        val execution = order.execution
        return BrokerOrderRecord(
            brokerOrderId = order.orderId,
            symbol = Symbol(marketOf(currency), order.symbol),
            side = OrderSide.valueOf(order.side),
            kind = OrderKind.valueOf(order.orderType),
            timeInForce = TimeInForce.valueOf(order.timeInForce),
            limitPrice = money(order.price),
            quantity = order.quantity?.let(Quantity::of),
            orderAmount = money(order.orderAmount),
            status = statusOf(order.status),
            filledQuantity = execution?.filledQuantity?.let(Quantity::of) ?: Quantity.ZERO,
            averageFilledPrice = money(execution?.averageFilledPrice),
            filledAmount = money(execution?.filledAmount),
            fee = money(execution?.commission),
            tax = money(execution?.tax),
            orderedAt = parse(order.orderedAt),
            filledAt = execution?.filledAt?.let(::parse),
            canceledAt = order.canceledAt?.let(::parse),
        )
    }

    /**
     * 토스 주문 상태 10개 → 도메인 상태.
     * 모르는 값은 UNKNOWN 으로 받아 조회로 확정하게 함.
     */
    fun statusOf(tossStatus: String): OrderStatus =
        when (tossStatus) {
            "PENDING" -> OrderStatus.PENDING
            "PARTIAL_FILLED" -> OrderStatus.PARTIALLY_FILLED
            "PENDING_CANCEL" -> OrderStatus.PENDING_CANCEL
            "PENDING_REPLACE" -> OrderStatus.PENDING_AMEND
            "FILLED" -> OrderStatus.FILLED
            "CANCELED" -> OrderStatus.CANCELLED
            "REJECTED" -> OrderStatus.REJECTED
            "CANCEL_REJECTED" -> OrderStatus.CANCEL_REJECTED
            "REPLACE_REJECTED" -> OrderStatus.AMEND_REJECTED
            "REPLACED" -> OrderStatus.REPLACED
            else -> OrderStatus.UNKNOWN
        }

    fun marketOf(currency: Currency): Market = Market.entries.single { it.currency == currency }

    fun parse(text: String): Instant = OffsetDateTime.parse(text).toInstant()
}
