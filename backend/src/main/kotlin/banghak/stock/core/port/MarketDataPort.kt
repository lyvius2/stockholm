package banghak.stock.core.port

import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.trading.CandleInterval
import banghak.stock.core.domain.trading.CandlePage
import banghak.stock.core.domain.trading.OrderBook
import banghak.stock.core.domain.trading.Quote
import java.time.Instant

/**
 * 시세 포트.
 * 공용 시세는 admin 의 토스 키로 받음(구성원 계좌와 무관).
 * 실패는 [banghak.stock.core.domain.error.MarketDataUnavailableException] 으로 올리고, 호출자는 마지막 캐시와 지연 표시로
 * 대신함.
 */
interface MarketDataPort {
    /**
     * 현재가 여러 건.
     * 한 번에 [MAX_SYMBOLS] 개까지.
     */
    fun quotes(symbols: List<Symbol>): List<Quote>

    /**
     * 봉 한 페이지.
     * [before] 가 null 이면 가장 최근 봉부터, [count] 는 [MAX_CANDLES] 까지.
     */
    fun candlePage(
        symbol: Symbol,
        interval: CandleInterval,
        before: Instant?,
        count: Int,
    ): CandlePage

    /**
     * 호가 전체 스냅샷.
     * 국내는 KRX·NXT 합산 호가라 매도 1호가가 매수 1호가보다 낮게 보일 수 있음.
     */
    fun orderBook(symbol: Symbol): OrderBook

    fun exchangeRate(from: Currency, to: Currency): ExchangeRate

    /**
     * [at] 시점의 환율.
     * 해외 체결의 원화 환산(매수·매도 시점 환율)에 씀.
     */
    fun exchangeRateAt(from: Currency, to: Currency, at: Instant): ExchangeRate

    companion object {
        const val MAX_SYMBOLS = 200
        const val MAX_CANDLES = 200
    }
}
