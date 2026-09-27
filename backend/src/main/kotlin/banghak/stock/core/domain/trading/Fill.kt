package banghak.stock.core.domain.trading

import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import java.time.Instant

/**
 * 체결.
 * 토스는 주문 1건에 체결 1행(평균가·총액·수수료·세금)만 주므로 주문 단위임.
 */
data class Fill(
    val brokerOrderId: String,
    val symbol: Symbol,
    val side: OrderSide,
    val price: Money,
    val quantity: Quantity,
    val fee: Money,
    val tax: Money,
    val executedAt: Instant,
) {
    fun grossAmount(): Money = price.times(quantity)
}
