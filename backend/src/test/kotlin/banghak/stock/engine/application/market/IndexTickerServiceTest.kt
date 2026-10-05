package banghak.stock.engine.application.market

import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.error.SecretMissingException
import banghak.stock.core.domain.market.IndexCode
import banghak.stock.core.domain.market.IndexQuote
import banghak.stock.core.domain.market.IndexSetState
import banghak.stock.core.domain.market.IndexSourceState
import banghak.stock.core.domain.market.IndicatorDailyClose
import banghak.stock.core.domain.market.IndicatorQuote
import banghak.stock.core.domain.market.MacroObservation
import banghak.stock.core.domain.market.MacroSeries
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.MarketIndicator
import banghak.stock.core.domain.market.MarketSession
import banghak.stock.core.domain.market.SessionWindow
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.market.TradingDay
import banghak.stock.core.domain.trading.Candle
import banghak.stock.core.domain.trading.CandleInterval
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.Quote
import banghak.stock.core.domain.trading.StreamMessage
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.FakeMarketData
import banghak.stock.support.fakes.MemoryCandleStore
import banghak.stock.support.fakes.MemoryIndexQuoteStore
import banghak.stock.support.fakes.MemoryMarketCalendarStore
import banghak.stock.support.fakes.RecordingMarketStream
import banghak.stock.support.fakes.ScriptedMacroIndicators
import banghak.stock.support.fakes.ScriptedMarketCalendar
import banghak.stock.support.fakes.ScriptedMarketIndicators
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class IndexTickerServiceTest {
    private val clock = MutableClock(at("2026-10-02T10:00:00+09:00"))
    private val calendar = ScriptedMarketCalendar()
    private val indicators = ScriptedMarketIndicators()
    private val marketData = FakeMarketData()
    private val macro = ScriptedMacroIndicators()
    private val store = MemoryIndexQuoteStore()
    private val stream = RecordingMarketStream()
    private val service =
        IndexTickerService(
            TradingCalendar(calendar, MemoryMarketCalendarStore(), clock),
            indicators,
            marketData,
            CandleHistory(marketData, MemoryCandleStore(), clock),
            macro,
            store,
            stream,
            clock,
        )

    @Test
    @DisplayName("한국 장중: KOSPI·KOSDAQ 는 현재가 + 전일 종가, NIKKEI 는 FRED 종가이며 모든 화면에 방송함")
    fun koreanOpenSet() {
        koreanRegular(LocalDate.of(2026, 10, 2))
        indicators.quotes[MarketIndicator.KOSPI] =
            IndicatorQuote(MarketIndicator.KOSPI, BigDecimal("3412.85"), clock.instant())
        indicators.quotes[MarketIndicator.KOSDAQ] =
            IndicatorQuote(MarketIndicator.KOSDAQ, BigDecimal("900.10"), clock.instant())
        indicators.dailyCloses[MarketIndicator.KOSPI] =
            listOf(
                close(MarketIndicator.KOSPI, "2026-10-02", "3410.00"),
                close(MarketIndicator.KOSPI, "2026-10-01", "3400.55"),
            )
        indicators.dailyCloses[MarketIndicator.KOSDAQ] =
            listOf(close(MarketIndicator.KOSDAQ, "2026-10-01", "899.00"))
        macro.observations[MacroSeries.NIKKEI225] =
            listOf(
                MacroObservation(LocalDate.of(2026, 10, 1), BigDecimal("66364.20")),
                MacroObservation(LocalDate.of(2026, 9, 30), BigDecimal("66000.00")),
            )

        val ticker = service.refresh()

        assertThat(ticker.choice.market).isEqualTo(Market.KR)
        assertThat(ticker.choice.state).isEqualTo(IndexSetState.OPEN)
        val kospi = ticker.quotes.first { it.code == IndexCode.KOSPI }
        // 오늘(진행 중) 봉은 건너뛰고 전일 종가 3400.55 대비
        assertThat(kospi.change).isEqualByComparingTo("12.30")
        assertThat(kospi.isClosed).isFalse()
        val nikkei = ticker.quotes.first { it.code == IndexCode.NIKKEI225 }
        assertThat(nikkei.isClosed).isTrue()
        assertThat(nikkei.change).isEqualByComparingTo("364.20")
        assertThat(ticker.isDelayed).isFalse()
        assertThat(ticker.entries.map { it.state }).containsOnly(IndexSourceState.FRESH)
        assertThat(stream.broadcasts).containsExactly(StreamMessage.IndexTickerUpdate(ticker))
        assertThat(store.rows).hasSize(3)
        assertThat(service.current()).isEqualTo(ticker)
    }

    @Test
    @DisplayName("첫 갱신 전 current 는 저장된 마지막 값으로 세트를 채워 지연으로 보이고, 값이 없던 지수는 빈 자리임")
    fun currentBeforeFirstRefreshUsesStoredValues() {
        koreanRegular(LocalDate.of(2026, 10, 2))
        store.saveAll(
            listOf(
                IndexQuote.of(
                    IndexCode.KOSPI,
                    BigDecimal("3400.55"),
                    null,
                    clock.instant().minusSeconds(600),
                    false,
                    null,
                    "TOSS",
                )
            )
        )

        val before = service.current()

        assertThat(before.choice.market).isEqualTo(Market.KR)
        assertThat(before.isDelayed).isTrue()
        assertThat(before.entries.first { it.code == IndexCode.KOSPI }.quote?.value)
            .isEqualByComparingTo("3400.55")
        assertThat(before.entries.first { it.code == IndexCode.KOSDAQ }.quote).isNull()
        assertThat(before.entries.first { it.code == IndexCode.KOSDAQ }.state)
            .isEqualTo(IndexSourceState.DELAYED)
    }

    @Test
    @DisplayName("FRED 키가 없으면 그 지수는 출처 미설정이고 지연으로 세지 않음")
    fun missingFredKeyIsUnconfigured() {
        koreanRegular(LocalDate.of(2026, 10, 2))
        indicators.quotes[MarketIndicator.KOSPI] =
            IndicatorQuote(MarketIndicator.KOSPI, BigDecimal("3412.85"), clock.instant())
        indicators.quotes[MarketIndicator.KOSDAQ] =
            IndicatorQuote(MarketIndicator.KOSDAQ, BigDecimal("900.10"), clock.instant())
        macro.failure = SecretMissingException("FRED 키가 등록되지 않음")

        val ticker = service.refresh()

        val nikkei = ticker.entries.first { it.code == IndexCode.NIKKEI225 }
        assertThat(nikkei.state).isEqualTo(IndexSourceState.UNCONFIGURED)
        assertThat(nikkei.quote).isNull()
        assertThat(ticker.isDelayed).isFalse()
    }

    @Test
    @DisplayName("긴 연휴 뒤 이른 아침에도 며칠 전에 더 늦게 닫힌 시장(미국)의 종가 세트를 고름")
    fun looksBackAcrossLongHoliday() {
        // 금 10/2 한국·미국 정규장 뒤 월·화 연휴, 수 10/7 06:00 KST(두 장 모두 닫힘, 한국 개장 3시간 전)
        koreanRegular(LocalDate.of(2026, 10, 2))
        usRegular(LocalDate.of(2026, 10, 2))
        clock.moveTo(at("2026-10-07T06:00:00+09:00"))

        val ticker = service.refresh()

        assertThat(ticker.choice.market).isEqualTo(Market.US)
        assertThat(ticker.choice.state).isEqualTo(IndexSetState.CLOSE)
    }

    @Test
    @DisplayName("미국 장중: ETF 프록시 현재가와 전일 종가, 프록시 티커를 표기함. 종가 세트면 FRED 지수값")
    fun usSets() {
        // 뉴욕 10/2 13:00 = KST 10/3 02:00
        clock.moveTo(at("2026-10-02T13:00:00-04:00"))
        usRegular(LocalDate.of(2026, 10, 2))
        val spy = Symbol(Market.US, "SPY")
        marketData.quotes[spy] = Quote(spy, TradingFixtures.usd("600.00"), clock.instant())
        dailyEtf(spy, "2026-10-02", "599.00")
        dailyEtf(spy, "2026-10-01", "590.00")

        val open = service.refresh()

        assertThat(open.choice.market).isEqualTo(Market.US)
        val sp500 = open.quotes.first { it.code == IndexCode.SP500 }
        assertThat(sp500.proxy).isEqualTo("SPY")
        assertThat(sp500.change).isEqualByComparingTo("10.00")
        // DJIA·NASDAQ 는 현재가가 없어 값이 비고 지연
        assertThat(open.quotes.map { it.code }).containsExactly(IndexCode.SP500)
        assertThat(open.entries.first { it.code == IndexCode.DJIA }.state)
            .isEqualTo(IndexSourceState.DELAYED)
        assertThat(open.isDelayed).isTrue()

        // 토요일 아침(두 장 닫힘) → 미국 종가 세트는 FRED
        clock.moveTo(at("2026-10-03T10:00:00+09:00"))
        macro.observations[MacroSeries.SP500] =
            listOf(MacroObservation(LocalDate.of(2026, 10, 2), BigDecimal("5700.00")))
        val closed = service.refresh()

        assertThat(closed.choice.state).isEqualTo(IndexSetState.CLOSE)
        val closedSp = closed.quotes.first { it.code == IndexCode.SP500 }
        assertThat(closedSp.source).isEqualTo("FRED")
        assertThat(closedSp.proxy).isNull()
        assertThat(closedSp.isClosed).isTrue()
    }

    @Test
    @DisplayName("새로 받지 못한 지수는 마지막 값을 두고 지연으로 표시하며, 재시작 뒤에는 저장된 마지막 값을 씀")
    fun keepsLastValueWhenSourceFails() {
        koreanRegular(LocalDate.of(2026, 10, 2))
        indicators.quotes[MarketIndicator.KOSPI] =
            IndicatorQuote(MarketIndicator.KOSPI, BigDecimal("3412.85"), clock.instant())
        indicators.dailyCloses[MarketIndicator.KOSPI] =
            listOf(close(MarketIndicator.KOSPI, "2026-10-01", "3400.55"))
        service.refresh()

        indicators.failure = MarketDataUnavailableException("토스 지표 없음")
        val stale = service.refresh()

        assertThat(stale.isDelayed).isTrue()
        assertThat(stale.quotes.first { it.code == IndexCode.KOSPI }.value)
            .isEqualByComparingTo("3412.85")

        val restarted =
            IndexTickerService(
                TradingCalendar(calendar, MemoryMarketCalendarStore(), clock),
                indicators,
                marketData,
                CandleHistory(marketData, MemoryCandleStore(), clock),
                macro,
                store,
                stream,
                clock,
            )
        assertThat(restarted.refresh().quotes.map { it.code }).contains(IndexCode.KOSPI)
    }

    private fun koreanRegular(date: LocalDate) {
        calendar.days[Market.KR to date] =
            TradingDay(
                Market.KR,
                date,
                listOf(
                    SessionWindow(
                        MarketSession.REGULAR,
                        at("${date}T09:00:00+09:00"),
                        at("${date}T15:30:00+09:00"),
                    )
                ),
            )
    }

    private fun usRegular(date: LocalDate) {
        calendar.days[Market.US to date] =
            TradingDay(
                Market.US,
                date,
                listOf(
                    SessionWindow(
                        MarketSession.REGULAR,
                        at("${date}T09:30:00-04:00"),
                        at("${date}T16:00:00-04:00"),
                    )
                ),
            )
    }

    private fun close(indicator: MarketIndicator, date: String, value: String) =
        IndicatorDailyClose(indicator, LocalDate.parse(date), BigDecimal(value))

    private fun dailyEtf(symbol: Symbol, kstDate: String, close: String) {
        val price = TradingFixtures.usd(close)
        marketData.candles +=
            Candle(
                symbol,
                CandleInterval.DAY_1,
                at("${kstDate}T00:00:00+09:00"),
                price,
                price,
                price,
                price,
                Quantity.of(1),
            )
    }

    private fun at(text: String): Instant = OffsetDateTime.parse(text).toInstant()
}
