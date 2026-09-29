package banghak.stock.core.port

import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.TradingDay
import java.time.LocalDate

/**
 * 장 달력 포트.
 * 휴장일은 세션이 빈 [TradingDay] 로 돌려줌.
 */
interface MarketCalendarPort {
    /** [date] 는 그 시장의 현지 날짜임(미국은 미국 동부 날짜). */
    fun tradingDay(market: Market, date: LocalDate): TradingDay
}
