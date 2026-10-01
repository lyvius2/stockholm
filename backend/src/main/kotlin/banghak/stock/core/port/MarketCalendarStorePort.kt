package banghak.stock.core.port

import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.TradingDay
import java.time.Instant
import java.time.LocalDate

/**
 * 장 달력 저장소(`market_calendar`).
 * 지난 날의 세션은 바뀌지 않으므로 한 번 받으면 다시 받지 않고, 오늘·앞날은 받은 시각을 보고 다시 받음.
 * 설치 공용이라 사용자 범위가 없음.
 */
interface MarketCalendarStorePort {
    /** 저장된 거래일과 받은 시각. */
    data class Stored(val day: TradingDay, val fetchedAt: Instant)

    fun find(market: Market, date: LocalDate): Stored?

    fun save(day: TradingDay, fetchedAt: Instant)
}
