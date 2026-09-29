package banghak.stock.core.port

import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.trading.CandleInterval
import banghak.stock.core.domain.trading.CandlePage
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

    fun exchangeRate(from: Currency, to: Currency): ExchangeRate

    companion object {
        const val MAX_SYMBOLS = 200
        const val MAX_CANDLES = 200
    }
}
