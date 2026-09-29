package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.money.RoundingRules
import java.time.Instant

/**
 * 주문 하나에서 새로 체결된 몫.
 * 토스는 주문마다 누적 체결 요약만 주므로 이미 반영된 요약과의 차이로 구함.
 * lot 은 이 증분마다 하나씩 생기고, 매도 증분은 lot 을 선입선출로 소진함.
 */
data class FillIncrement(
    val userId: UserId,
    val brokerOrderId: String,
    val symbol: Symbol,
    val side: OrderSide,
    val quantity: Quantity,
    val amount: Money,
    val fee: Money,
    val tax: Money,
    val orderOrigin: OrderOrigin,
    val executedAt: Instant,
) {
    init {
        if (quantity.isZero) throw InvalidValueException("체결 증분 수량은 0보다 커야 함")
        val currency = symbol.market.currency
        if (listOf(amount, fee, tax).any { it.currency != currency })
            throw InvalidValueException("$symbol 체결 금액은 $currency 여야 함")
    }

    /**
     * 이 증분의 평균 단가.
     * 통화 자릿수로 반올림함(원화는 1원 단위).
     */
    fun unitPrice(): Money =
        Money.of(
            amount.amount.divide(quantity.value, PRICE_SCALE, RoundingRules.MONEY),
            amount.currency,
        )

    companion object {
        private const val PRICE_SCALE = 10

        /**
         * 이미 대기열에 넣은 요약([queued])과 증권사 누적 체결 요약의 차이.
         * 체결 수량이 늘지 않았거나 체결 금액이 아직 없으면 null(금액이 채워진 뒤 넣지 못한 수량 전체가 한 증분이 됨).
         * 실시간 채널은 체결 시각을 주지 않으므로 그때는 [receivedAt] 을 체결 시각으로 씀.
         */
        fun between(
            userId: UserId,
            queued: FillSummary,
            record: BrokerOrderRecord,
            origin: OrderOrigin,
            receivedAt: Instant,
        ): FillIncrement? {
            if (!record.filledQuantity.isGreaterThan(queued.quantity)) return null
            val amountNow = filledAmountOf(record) ?: return null
            val zero = Money.zero(record.symbol.market.currency)
            return FillIncrement(
                userId = userId,
                brokerOrderId = record.brokerOrderId,
                symbol = record.symbol,
                side = record.side,
                quantity = record.filledQuantity.minus(queued.quantity),
                amount = amountNow.minus(queued.amount),
                fee = nonNegative((record.fee ?: zero).minus(queued.fee)),
                tax = nonNegative((record.tax ?: zero).minus(queued.tax)),
                orderOrigin = origin,
                executedAt = record.filledAt ?: receivedAt,
            )
        }

        private fun filledAmountOf(record: BrokerOrderRecord): Money? =
            record.filledAmount ?: record.averageFilledPrice?.times(record.filledQuantity)

        // 수수료·세금 누적값이 드물게 줄어 보여도(정정·재계산) 음수 비용은 만들지 않음
        private fun nonNegative(money: Money): Money =
            if (money.isNegative) Money.zero(money.currency) else money
    }
}

/**
 * 주문 하나에서 이미 대기열에 넣은 체결의 누적 요약.
 * 증권사가 알려 준 누적 체결 수량과 따로 두어, 금액이 늦게 채워진 체결도 나중에 넣을 수 있게 함.
 */
data class FillSummary(val quantity: Quantity, val amount: Money, val fee: Money, val tax: Money) {
    fun plus(increment: FillIncrement): FillSummary =
        FillSummary(
            quantity.plus(increment.quantity),
            amount.plus(increment.amount),
            fee.plus(increment.fee),
            tax.plus(increment.tax),
        )

    companion object {
        fun zero(currency: Currency): FillSummary =
            FillSummary(
                Quantity.ZERO,
                Money.zero(currency),
                Money.zero(currency),
                Money.zero(currency),
            )
    }
}
