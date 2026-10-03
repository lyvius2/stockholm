package banghak.stock.engine.application.market

import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.MarketSession
import banghak.stock.core.domain.market.RankChange
import banghak.stock.core.domain.market.RankedStock
import banghak.stock.core.domain.market.Ranking
import banghak.stock.core.domain.market.RankingQuery
import banghak.stock.core.domain.market.RankingType
import banghak.stock.core.domain.market.SessionWindow
import banghak.stock.core.domain.market.StockFlags
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.market.TradingDay
import banghak.stock.core.domain.trading.Quote
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.port.RankingPort
import banghak.stock.core.usecase.LookupStockFlagsUseCase
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.FakeMarketData
import banghak.stock.support.fakes.MemoryMarketCalendarStore
import banghak.stock.support.fakes.ScriptedMarketCalendar
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class MoversServiceTest {
    // 한국 시간 2026-10-02 10:00(정규장)
    private val clock = MutableClock(Instant.parse("2026-10-02T01:00:00Z"))
    private val marketData = FakeMarketData()
    private val calendar = ScriptedMarketCalendar()
    private val rankings =
        object : RankingPort {
            val byType = mutableMapOf<RankingType, List<RankedStock>>()
            val queries = mutableListOf<RankingQuery>()
            var failure: RuntimeException? = null

            override fun ranking(query: RankingQuery): Ranking {
                queries += query
                failure?.let { throw it }
                return Ranking(clock.instant(), byType[query.type].orEmpty())
            }
        }
    private val flagged = mutableMapOf<Symbol, StockFlags>()
    private val service =
        MoversService(
            rankings,
            marketData,
            object : LookupStockFlagsUseCase {
                override fun flags(symbol: Symbol): StockFlags =
                    flagged[symbol] ?: throw MarketDataUnavailableException("경고 없음")
            },
            TradingCalendar(calendar, MemoryMarketCalendarStore(), clock),
            clock,
        )

    @Test
    @DisplayName("급등·급락 랭킹 100씩을 받아 현재가로 다시 정렬하고, 세션과 경고를 붙임")
    fun buildsBoardFromRankingsAndQuotes() {
        val date = LocalDate.of(2026, 10, 2)
        calendar.days[Market.KR to date] =
            TradingDay(
                Market.KR,
                date,
                listOf(
                    SessionWindow(
                        MarketSession.REGULAR,
                        Instant.parse("2026-10-02T00:00:00Z"),
                        Instant.parse("2026-10-02T06:30:00Z"),
                    )
                ),
            )
        rankings.byType[RankingType.TOP_GAINERS] =
            listOf(ranked("A00001", "1100", "1000"), ranked("B00002", "1050", "1000"))
        rankings.byType[RankingType.TOP_LOSERS] = listOf(ranked("C00003", "900", "1000"))
        marketData.quotes[symbol("A00001")] = Quote(symbol("A00001"), krw("1100"), clock.instant())
        marketData.quotes[symbol("B00002")] = Quote(symbol("B00002"), krw("1300"), clock.instant())
        marketData.quotes[symbol("C00003")] = Quote(symbol("C00003"), krw("900"), clock.instant())
        flagged[symbol("B00002")] =
            StockFlags(
                symbol("B00002"),
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                null,
                null,
                clock.instant(),
            )

        val board = service.board(Market.KR)

        assertThat(rankings.queries.map { it.type })
            .containsExactly(RankingType.TOP_GAINERS, RankingType.TOP_LOSERS)
        assertThat(rankings.queries.first().count).isEqualTo(100)
        assertThat(board.gainers.map { it.symbol.code }).startsWith("B00002", "A00001")
        assertThat(board.gainers[0].flags?.isInvestmentWarning).isTrue()
        assertThat(board.losers.first().symbol.code).isEqualTo("C00003")
        assertThat(board.session).isEqualTo(MarketSession.REGULAR)
        assertThat(board.isDelayed).isFalse()
    }

    @Test
    @DisplayName("10초 안의 재요청은 같은 판이고, 10초가 지나면 다시 만들어 순위 변동을 매김")
    fun refreshesEveryTenSeconds() {
        rankings.byType[RankingType.TOP_GAINERS] =
            listOf(ranked("A00001", "1100", "1000"), ranked("B00002", "1050", "1000"))

        val first = service.board(Market.KR)
        clock.advance(Duration.ofSeconds(9))
        assertThat(service.board(Market.KR)).isSameAs(first)
        assertThat(rankings.queries).hasSize(2)

        marketData.quotes[symbol("B00002")] = Quote(symbol("B00002"), krw("1300"), clock.instant())
        clock.advance(Duration.ofSeconds(1))
        val second = service.board(Market.KR)

        assertThat(rankings.queries).hasSize(4)
        assertThat(second.gainers.map { it.symbol.code to it.rankChange })
            .containsExactly("B00002" to RankChange.Up(1), "A00001" to RankChange.Down(1))
    }

    @Test
    @DisplayName("랭킹을 받지 못하면 직전 판을 지연으로 돌려주고, 직전 판도 없으면 예외를 올림")
    fun fallsBackToPreviousBoard() {
        rankings.failure = MarketDataUnavailableException("토스 랭킹 없음")
        assertThatThrownBy { service.board(Market.US) }
            .isInstanceOf(MarketDataUnavailableException::class.java)

        rankings.failure = null
        rankings.byType[RankingType.TOP_GAINERS] = listOf(ranked("A00001", "1100", "1000"))
        val first = service.board(Market.KR)
        clock.advance(Duration.ofSeconds(11))
        rankings.failure = MarketDataUnavailableException("토스 랭킹 없음")

        val stale = service.board(Market.KR)

        assertThat(stale.gainers).isEqualTo(first.gainers)
        assertThat(stale.isDelayed).isTrue()
        assertThat(stale.asOf).isEqualTo(clock.instant())
    }

    @Test
    @DisplayName("현재가 응답에서 한 종목이라도 빠지면 옛 가격으로 계산한 것이라 지연으로 표시함")
    fun partialQuotesAreDelayed() {
        rankings.byType[RankingType.TOP_GAINERS] =
            listOf(ranked("A00001", "1100", "1000"), ranked("B00002", "1050", "1000"))
        marketData.quotes[symbol("A00001")] = Quote(symbol("A00001"), krw("1100"), clock.instant())

        assertThat(service.board(Market.KR).isDelayed).isTrue()
    }

    @Test
    @DisplayName("랭킹 실패로 직전 판을 쓸 때도 장 세션은 지금 달력으로 다시 봄")
    fun staleBoardRefreshesSession() {
        val date = LocalDate.of(2026, 10, 2)
        calendar.days[Market.KR to date] =
            TradingDay(
                Market.KR,
                date,
                listOf(
                    SessionWindow(
                        MarketSession.REGULAR,
                        Instant.parse("2026-10-02T00:00:00Z"),
                        Instant.parse("2026-10-02T06:30:00Z"),
                    )
                ),
            )
        rankings.byType[RankingType.TOP_GAINERS] = listOf(ranked("A00001", "1100", "1000"))
        assertThat(service.board(Market.KR).session).isEqualTo(MarketSession.REGULAR)

        // 정규장이 끝난 뒤 랭킹이 실패함
        clock.moveTo(Instant.parse("2026-10-02T07:00:00Z"))
        rankings.failure = MarketDataUnavailableException("토스 랭킹 없음")

        val stale = service.board(Market.KR)

        assertThat(stale.isDelayed).isTrue()
        assertThat(stale.session).isEqualTo(MarketSession.CLOSED)
    }

    private fun symbol(code: String) = Symbol(Market.KR, code)

    private fun ranked(code: String, last: String, base: String) =
        RankedStock(1, symbol(code), krw(last), krw(base), null, BigDecimal.ONE, krw("1000"))
}
