package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.money.RoundingRules
import banghak.stock.core.domain.trading.Quantity

/**
 * 한 사용자의 한 종목 보유.
 * 열린 lot 의 묶음이며 수량·평균 단가는 lot 에서 계산함.
 * 다른 사용자의 lot 이 섞이면 생성 자체를 거부함(사용자 간 경계).
 */
data class Position(val userId: UserId, val symbol: Symbol, val openLots: List<Lot>) {
    init {
        openLots
            .firstOrNull { it.userId != userId || it.symbol != symbol || !it.isOpen }
            ?.let { throw InvalidValueException("포지션 $userId/$symbol 에 맞지 않는 lot 임: ${it.id}") }
    }

    fun quantity(): Quantity =
        openLots.fold(Quantity.ZERO) { sum, lot -> sum.plus(lot.remainingQuantity) }

    /**
     * 잔여 수량 가중 평균 매입 단가.
     * 보유가 없으면 0.
     */
    fun averageCost(): Money {
        val currency = symbol.market.currency
        val quantity = quantity()
        if (quantity.isZero) return Money.zero(currency)
        val totalCost =
            openLots.fold(Money.zero(currency)) { sum, lot -> sum.plus(lot.costBasis()) }
        return Money.of(
            totalCost.amount.divide(quantity.value, currency.scale, RoundingRules.MONEY),
            currency,
        )
    }

    fun quantityFrom(origin: BuyOrigin): Quantity =
        openLots
            .filter { it.origin == origin }
            .fold(Quantity.ZERO) { sum, lot -> sum.plus(lot.remainingQuantity) }

    /**
     * 현지 통화 평가금액(현재가 × 수량).
     * 원화 환산은 호출자가 환율로 함.
     */
    fun marketValue(lastPrice: Money): Money = lastPrice.times(quantity())

    companion object {
        /**
         * 한 사용자의 lot 목록을 종목별 포지션으로 묶음.
         * 청산된 lot 은 빼고, 다른 사용자의 lot 이 섞여 있으면 거부함.
         */
        fun fromLots(userId: UserId, lots: List<Lot>): List<Position> {
            lots
                .firstOrNull { it.userId != userId }
                ?.let {
                    throw InvalidValueException("$userId 의 포지션에 다른 사용자의 lot 이 섞임: ${it.id}")
                }
            return lots
                .filter { it.isOpen }
                .groupBy { it.symbol }
                .map { (symbol, open) -> Position(userId, symbol, open) }
        }
    }
}
