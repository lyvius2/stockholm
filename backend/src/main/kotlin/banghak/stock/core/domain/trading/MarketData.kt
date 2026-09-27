package banghak.stock.core.domain.trading

import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import java.time.Duration
import java.time.Instant

/**
 * 현재가.
 * 등락은 전일 종가 대비로 화면이 계산함.
 */
data class Quote(
    val symbol: Symbol,
    val last: Money,
    val prevClose: Money,
    val volume: Quantity,
    val asOf: Instant,
)

/**
 * 호가.
 * 매도 호가는 낮은 가격부터, 매수 호가는 높은 가격부터임.
 */
data class OrderBook(
    val symbol: Symbol,
    val asks: List<Level>,
    val bids: List<Level>,
    val asOf: Instant,
) {
    data class Level(val price: Money, val quantity: Quantity)
}

/**
 * 봉.
 * 토스 1분봉의 timestamp 는 봉 종료 시각이므로 어댑터가 [openTime] 으로 바꿔 넣음.
 */
data class Candle(
    val symbol: Symbol,
    val interval: Duration,
    val openTime: Instant,
    val open: Money,
    val high: Money,
    val low: Money,
    val close: Money,
    val volume: Quantity,
)
