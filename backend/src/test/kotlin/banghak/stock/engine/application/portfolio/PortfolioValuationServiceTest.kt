package banghak.stock.engine.application.portfolio

import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.portfolio.BrokerHolding
import banghak.stock.core.domain.trading.Candle
import banghak.stock.core.domain.trading.CandleInterval
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.Quote
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.engine.application.market.CandleHistory
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.FakeMarketData
import banghak.stock.support.fakes.FakeTradingPort
import banghak.stock.support.fakes.MemoryCandleStore
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PortfolioValuationServiceTest {
    // 한국 시간 2026-10-01 10:00
    private val clock = MutableClock(Instant.parse("2026-10-01T01:00:00Z"))
    private val user = TradingFixtures.user
    private val samsung = TradingFixtures.samsung
    private val trading = FakeTradingPort()
    private val marketData = FakeMarketData()
    private val service =
        PortfolioValuationService(
            trading,
            marketData,
            CandleHistory(marketData, MemoryCandleStore(), clock),
            clock,
        )

    @Test
    @DisplayName("보유·현재가·전일 종가·환율·매수 가능 금액을 모아 평가하고 모두 받았으면 지연이 아님")
    fun gathersEverything() {
        trading.holdings += holding(samsung, 10, "65000", "70000")
        marketData.quotes[samsung] = Quote(samsung, krw("71000"), clock.instant())
        marketData.rates[Currency.USD to Currency.KRW] =
            ExchangeRate(Currency.USD, Currency.KRW, BigDecimal("1400"), clock.instant())
        // 오늘 봉(진행 중)과 전일 봉
        daily(kst("2026-10-01T00:00:00"), close = "71000")
        daily(kst("2026-09-30T00:00:00"), close = "69000")
        trading.buyingPower[Currency.KRW] = krw("500000")
        trading.buyingPower[Currency.USD] = TradingFixtures.usd("100.00")

        val valuation = service.valuation(user)

        val kr = valuation.byMarket.getValue(Market.KR)
        assertThat(kr.marketValue).isEqualTo(krw("710000"))
        assertThat(kr.todayChange).isEqualTo(krw("20000"))
        assertThat(valuation.cashBuyingPower).containsKeys(Currency.KRW, Currency.USD)
        assertThat(valuation.isDelayed).isFalse()
        assertThat(valuation.fxUsdKrw).isNull()
    }

    @Test
    @DisplayName("현재가·전일 종가·매수 가능 금액을 받지 못하면 증권사 값으로 평가하고 지연으로 표시함")
    fun marksDelayedWhenReferenceDataIsMissing() {
        trading.holdings += holding(samsung, 10, "65000", "70000")
        marketData.candleFailure = MarketDataUnavailableException("토스 시세 없음")

        val valuation = service.valuation(user)

        assertThat(valuation.byMarket.getValue(Market.KR).marketValue).isEqualTo(krw("700000"))
        assertThat(valuation.byMarket.getValue(Market.KR).todayChange).isNull()
        assertThat(valuation.isDelayed).isTrue()
    }

    @Test
    @DisplayName("미국 장중(한국 시간 새벽)에는 진행 중인 오늘 봉이 아니라 미국 전 거래일 종가를 전일 종가로 씀")
    fun usPreviousCloseFollowsEasternTradingDay() {
        // 한국 10/2 02:00 = 뉴욕 10/1 13:00(장중).
        // 토스 일봉 시각은 거래일 0시 KST
        clock.moveTo(Instant.parse("2026-10-01T17:00:00Z"))
        val nvidia = TradingFixtures.nvidia
        trading.holdings += holding(nvidia, 1, "100.00", "120.00")
        usDaily(kst("2026-10-01T00:00:00"), close = "120.00")
        usDaily(kst("2026-09-30T00:00:00"), close = "110.00")
        marketData.rates[Currency.USD to Currency.KRW] =
            ExchangeRate(Currency.USD, Currency.KRW, BigDecimal("1400"), clock.instant())

        val us = service.valuation(user).byMarket.getValue(Market.US)

        assertThat(us.holdings.single().previousClose).isEqualTo(TradingFixtures.usd("110.00"))
        assertThat(us.todayChange).isEqualTo(TradingFixtures.usd("10.00"))
    }

    @Test
    @DisplayName("전일 종가는 받은 지 한 시간 안이면 저장된 일봉을 써 보유 종목마다 토스를 부르지 않음")
    fun previousCloseUsesStoredDailyCandles() {
        trading.holdings += holding(samsung, 10, "65000", "70000")
        daily(kst("2026-10-01T00:00:00"), close = "71000")
        daily(kst("2026-09-30T00:00:00"), close = "69000")

        service.valuation(user)
        val readsAfterFirst = marketData.candleReads
        service.valuation(user)
        assertThat(marketData.candleReads).isEqualTo(readsAfterFirst)

        clock.advance(java.time.Duration.ofMinutes(61))
        service.valuation(user)
        assertThat(marketData.candleReads).isEqualTo(readsAfterFirst + 1)
    }

    @Test
    @DisplayName("일봉을 새로 받지 못해 저장된 것으로 때우면 지연으로 표시함")
    fun storedDailyFallbackIsDelayed() {
        trading.holdings += holding(samsung, 10, "65000", "70000")
        daily(kst("2026-09-30T00:00:00"), close = "69000")
        marketData.quotes[samsung] = Quote(samsung, krw("71000"), clock.instant())
        service.valuation(user)
        clock.advance(java.time.Duration.ofHours(2))
        marketData.candleFailure = MarketDataUnavailableException("토스 시세 없음")

        val valuation = service.valuation(user)

        assertThat(valuation.byMarket.getValue(Market.KR).todayChange).isEqualTo(krw("20000"))
        assertThat(valuation.isDelayed).isTrue()
    }

    @Test
    @DisplayName("보유가 200종목을 넘어도 현재가를 나눠 묻어 평가함")
    fun quotesAreChunked() {
        repeat(201) { index ->
            val symbol =
                banghak.stock.core.domain.market.Symbol(
                    Market.KR,
                    index.toString().padStart(6, '0'),
                )
            trading.holdings += holding(symbol, 1, "1000", "1000")
            marketData.quotes[symbol] = Quote(symbol, krw("1100"), clock.instant())
        }

        val valuation = service.valuation(user)

        assertThat(valuation.byMarket.getValue(Market.KR).marketValue).isEqualTo(krw("221100"))
    }

    @Test
    @DisplayName("보유를 받지 못하면 예외를 그대로 올림")
    fun holdingsFailurePropagates() {
        trading.accountFailure = BrokerUnavailableException("토스에 연결할 수 없음")

        assertThatThrownBy { service.valuation(user) }
            .isInstanceOf(BrokerUnavailableException::class.java)
    }

    private fun usDaily(openTime: Instant, close: String) {
        val price = TradingFixtures.usd(close)
        marketData.candles +=
            Candle(
                TradingFixtures.nvidia,
                CandleInterval.DAY_1,
                openTime,
                price,
                price,
                price,
                price,
                Quantity.of(1),
            )
    }

    private fun daily(openTime: Instant, close: String) {
        val price = krw(close)
        marketData.candles +=
            Candle(
                samsung,
                CandleInterval.DAY_1,
                openTime,
                price,
                price,
                price,
                price,
                Quantity.of(1),
            )
    }

    private fun holding(
        symbol: banghak.stock.core.domain.market.Symbol,
        quantity: Long,
        avg: String,
        last: String,
    ): BrokerHolding {
        val currency = symbol.market.currency
        val average = banghak.stock.core.domain.money.Money.of(avg, currency)
        val lastPrice = banghak.stock.core.domain.money.Money.of(last, currency)
        val shares = Quantity.of(quantity)
        return BrokerHolding(
            symbol,
            shares,
            average,
            lastPrice,
            average.times(shares),
            lastPrice.times(shares),
            lastPrice.times(shares).minus(average.times(shares)),
        )
    }

    private fun kst(text: String): Instant = OffsetDateTime.parse("$text+09:00").toInstant()
}
