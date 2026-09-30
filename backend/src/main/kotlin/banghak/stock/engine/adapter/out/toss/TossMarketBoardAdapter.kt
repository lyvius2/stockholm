package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.market.IndicatorQuote
import banghak.stock.core.domain.market.MarketIndicator
import banghak.stock.core.domain.market.RankedStock
import banghak.stock.core.domain.market.Ranking
import banghak.stock.core.domain.market.RankingPeriod
import banghak.stock.core.domain.market.RankingQuery
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.money.Percent
import banghak.stock.core.port.MarketIndicatorPort
import banghak.stock.core.port.RankingPort
import banghak.stock.shared.config.RuntimeProfiles
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.ratelimiter.annotation.RateLimiter
import java.time.OffsetDateTime
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 토스 랭킹·시장 지표 어댑터.
 * 계좌와 무관한 공용 정보라 admin 의 키로 부름.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class TossMarketBoardAdapter(
    private val rankings: TossRankingClient,
    private val indicators: TossIndicatorClient,
    private val callers: TossCallerResolver,
) : RankingPort, MarketIndicatorPort {
    @RateLimiter(name = "toss-ranking")
    @CircuitBreaker(name = "toss-ranking", fallbackMethod = "rankingUnavailable")
    override fun ranking(query: RankingQuery): Ranking {
        val result =
            TossResponses.marketResultOf(
                rankings
                    .rankings(
                        callers.publicMarketCaller(),
                        query.type.name,
                        query.market.name,
                        durationCode(query.period),
                        query.excludesInvestmentCaution,
                        query.count,
                    )
                    .execute()
            )
        return Ranking(
            result.rankedAt?.let { OffsetDateTime.parse(it).toInstant() },
            result.rankings.map { toRankedStock(query, it) },
        )
    }

    fun rankingUnavailable(query: RankingQuery, cause: Throwable): Ranking =
        TossResponses.marketFallback(cause)

    // 모르는 심볼과 요청하지 않은 지표는 버림
    @RateLimiter(name = "toss-market-indicator")
    @CircuitBreaker(name = "toss-market-indicator", fallbackMethod = "indicatorQuotesUnavailable")
    override fun indicatorQuotes(indicators: List<MarketIndicator>): List<IndicatorQuote> {
        if (indicators.isEmpty()) return emptyList()
        val requested = indicators.associateBy { it.name }
        return TossResponses.marketResultOf(
                this.indicators
                    .prices(callers.publicMarketCaller(), indicators.joinToString(",") { it.name })
                    .execute()
            )
            .mapNotNull { price ->
                requested[price.symbol]?.let {
                    IndicatorQuote(
                        it,
                        price.lastPrice,
                        price.timestamp?.let { at -> OffsetDateTime.parse(at).toInstant() },
                    )
                }
            }
    }

    fun indicatorQuotesUnavailable(
        indicators: List<MarketIndicator>,
        cause: Throwable,
    ): List<IndicatorQuote> = TossResponses.marketFallback(cause)

    // 응답 통화가 시장 통화와 다르면 잘못된 금액이 되므로 조회 실패로 봄
    private fun toRankedStock(query: RankingQuery, item: TossRankingItem): RankedStock {
        val currency = query.market.currency
        if (item.currency != currency.name)
            throw MarketDataUnavailableException(
                "${query.market} 랭킹은 $currency 인데 토스가 ${item.currency} 로 줌"
            )
        return RankedStock(
            rank = item.rank,
            symbol = Symbol(query.market, item.symbol),
            last = Money.of(item.price.lastPrice, currency),
            base = Money.of(item.price.basePrice, currency),
            changeRate = item.price.changeRate?.let { Percent(it) },
            tradingVolume = item.tradingVolume,
            tradingAmount = Money.of(item.tradingAmount, currency),
        )
    }

    private fun durationCode(period: RankingPeriod): String =
        when (period) {
            RankingPeriod.REALTIME -> "realtime"
            RankingPeriod.DAY_1 -> "1d"
            RankingPeriod.WEEK_1 -> "1w"
            RankingPeriod.MONTH_1 -> "1mo"
            RankingPeriod.MONTH_3 -> "3mo"
            RankingPeriod.MONTH_6 -> "6mo"
            RankingPeriod.YEAR_1 -> "1y"
        }
}
