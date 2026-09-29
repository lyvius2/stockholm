package banghak.stock.core.domain.trading

import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import java.time.Duration
import java.time.Instant

/**
 * 현재가.
 * 토스 현재가 API 는 마지막 체결가와 시각만 줌.
 * 전일 종가·거래량은 일봉에서 구함.
 * 시각을 모르는 값은 [asOf] 를 아주 오래된 시각으로 둬 신선도 검사에서 걸러지게 함.
 */
data class Quote(val symbol: Symbol, val last: Money, val asOf: Instant)

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
 * 증권사가 주는 봉 단위.
 * 토스는 1분봉과 일봉만 줌.
 * 나머지는 데몬이 집계함.
 */
enum class CandleInterval(val length: Duration) {
    MINUTE_1(Duration.ofMinutes(1)),
    DAY_1(Duration.ofDays(1)),
}

/**
 * 봉.
 * 토스 1분봉의 timestamp 는 봉 종료 시각이므로 어댑터가 [openTime] 으로 바꿔 넣음.
 */
data class Candle(
    val symbol: Symbol,
    val interval: CandleInterval,
    val openTime: Instant,
    val open: Money,
    val high: Money,
    val low: Money,
    val close: Money,
    val volume: Quantity,
)

/**
 * 봉 한 페이지.
 * [nextBefore] 를 다음 요청에 그대로 넘기며 null 이면 끝임.
 */
data class CandlePage(val candles: List<Candle>, val nextBefore: Instant?)
