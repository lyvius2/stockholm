package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.Quantity
import java.time.Instant

/**
 * 증권사가 아는 보유 한 종목.
 * 금액은 모두 그 종목의 거래 통화임.
 * 출처(lot) 구분은 증권사가 모르므로 engine 이 로컬 lot 과 합쳐 씀.
 */
data class BrokerHolding(
    val symbol: Symbol,
    val quantity: Quantity,
    val averagePurchasePrice: Money,
    val lastPrice: Money,
    val purchaseAmount: Money,
    val marketValue: Money,
    val profitLoss: Money,
) {
    init {
        val currency = symbol.market.currency
        listOf(averagePurchasePrice, lastPrice, purchaseAmount, marketValue, profitLoss)
            .firstOrNull { it.currency != currency }
            ?.let { throw InvalidValueException("$symbol 보유 금액은 $currency 여야 함: $it") }
    }
}

/**
 * 증권사 보유 조회 결과.
 * [marketValueKrw] 는 전체 평가금액의 원화 합계(토스 계산값)임.
 */
data class BrokerHoldings(
    val items: List<BrokerHolding>,
    val marketValueKrw: Money,
    val asOf: Instant,
) {
    init {
        if (marketValueKrw.currency != Currency.KRW)
            throw InvalidValueException("원화 합계는 KRW 여야 함: $marketValueKrw")
    }
}
