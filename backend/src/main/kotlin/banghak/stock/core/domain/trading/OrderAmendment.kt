package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.money.Money

/**
 * 정정 요청.
 * 지정가만(시장가 전환 없음).
 * 가격·수량 중 하나는 있어야 함.
 */
data class OrderAmendment(val newLimitPrice: Money?, val newQuantity: Quantity?) {
    init {
        if (newLimitPrice == null && newQuantity == null)
            throw InvalidValueException("정정에는 새 가격이나 새 수량 중 하나는 있어야 함")
        if (newLimitPrice != null && !newLimitPrice.isPositive)
            throw InvalidValueException("정정 가격은 0보다 커야 함: $newLimitPrice")
        if (newQuantity != null && newQuantity.isZero)
            throw InvalidValueException("정정 수량은 0보다 커야 함")
    }

    companion object {
        /**
         * 시장 규격을 함께 검사함.
         * 토스는 미국 주문의 수량 정정을 받지 않음(국내는 가격+수량).
         */
        fun forMarket(
            market: Market,
            newLimitPrice: Money?,
            newQuantity: Quantity?,
        ): OrderAmendment {
            if (market == Market.US && newQuantity != null)
                throw InvalidValueException("미국 주문은 가격만 정정할 수 있음")
            if (newLimitPrice != null && newLimitPrice.currency != market.currency)
                throw InvalidValueException("$market 정정 가격은 ${market.currency} 여야 함")
            return OrderAmendment(newLimitPrice, newQuantity)
        }
    }
}
