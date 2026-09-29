package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.MarketSession
import banghak.stock.core.domain.market.SessionWindow
import banghak.stock.core.domain.market.TradingDay
import banghak.stock.core.port.MarketCalendarPort
import banghak.stock.shared.config.RuntimeProfiles
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.ratelimiter.annotation.RateLimiter
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 토스 장 달력 어댑터.
 * 국내는 KRX+NXT 통합 세션(프리·정규·애프터, 동시호가 시각 포함), 미국은 데이마켓·프리·정규·애프터를 KST 시각으로 줌.
 * 휴장일은 국내 `integrated` 가 null, 미국은 세션이 모두 null 임.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class TossMarketCalendarAdapter(
    private val marketInfo: TossMarketInfoClient,
    private val callers: TossCallerResolver,
) : MarketCalendarPort {
    @RateLimiter(name = "toss-market-info")
    @CircuitBreaker(name = "toss-market-info", fallbackMethod = "calendarUnavailable")
    override fun tradingDay(market: Market, date: LocalDate): TradingDay =
        when (market) {
            Market.KR ->
                koreanDay(
                    date,
                    TossResponses.marketResultOf(
                        marketInfo
                            .koreanCalendar(callers.publicMarketCaller(), date.toString())
                            .execute()
                    ),
                )
            Market.US ->
                usDay(
                    date,
                    TossResponses.marketResultOf(
                        marketInfo
                            .usCalendar(callers.publicMarketCaller(), date.toString())
                            .execute()
                    ),
                )
        }

    fun calendarUnavailable(market: Market, date: LocalDate, cause: Throwable): TradingDay =
        TossResponses.marketFallback(cause)

    private fun koreanDay(date: LocalDate, calendar: TossKrCalendar): TradingDay {
        requireSameDate(date, calendar.today.date)
        val sessions = calendar.today.integrated
        return TradingDay(
            Market.KR,
            date,
            listOfNotNull(
                sessions?.preMarket?.let { window(MarketSession.PRE, it) },
                sessions?.regularMarket?.let { window(MarketSession.REGULAR, it) },
                sessions?.afterMarket?.let { window(MarketSession.AFTER, it) },
            ),
        )
    }

    private fun usDay(date: LocalDate, calendar: TossUsCalendar): TradingDay {
        val today = calendar.today
        requireSameDate(date, today.date)
        return TradingDay(
            Market.US,
            date,
            listOfNotNull(
                today.dayMarket?.let { window(MarketSession.DAY_MARKET, it) },
                today.preMarket?.let { window(MarketSession.PRE, it) },
                today.regularMarket?.let { window(MarketSession.REGULAR, it) },
                today.afterMarket?.let { window(MarketSession.AFTER, it) },
            ),
        )
    }

    private fun window(session: MarketSession, dto: TossSession): SessionWindow =
        SessionWindow(
            session,
            parse(dto.startTime),
            parse(dto.endTime),
            auctionStart = dto.singlePriceAuctionStartTime?.let(::parse),
            auctionEnd = dto.singlePriceAuctionEndTime?.let(::parse),
        )

    // 다른 날의 세션을 요청한 날의 장 정보로 돌려주지 않음
    private fun requireSameDate(requested: LocalDate, answered: String) {
        if (answered != requested.toString())
            throw MarketDataUnavailableException("$requested 달력을 요청했는데 $answered 가 옴")
    }

    private fun parse(text: String): Instant = OffsetDateTime.parse(text).toInstant()
}
