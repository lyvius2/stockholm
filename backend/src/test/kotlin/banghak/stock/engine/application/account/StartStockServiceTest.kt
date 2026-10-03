package banghak.stock.engine.application.account

import banghak.stock.core.domain.account.UserSetting
import banghak.stock.core.domain.account.UserSettingCodec
import banghak.stock.core.domain.account.UserSettingKey
import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.eventlog.UserSettingChanged
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.portfolio.BrokerHolding
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.StartStock
import banghak.stock.core.domain.trading.StartStockReason
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.FakeMarketData
import banghak.stock.support.fakes.FakeTradingPort
import banghak.stock.support.fakes.MemoryEventStore
import banghak.stock.support.fakes.MemoryStockMaster
import banghak.stock.support.fakes.MemoryUserSettings
import java.math.BigDecimal
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class StartStockServiceTest {
    private val clock = MutableClock(TradingFixtures.now)
    private val user = TradingFixtures.user
    private val settingsStore = MemoryUserSettings()
    private val events = MemoryEventStore()
    private val settings = UserSettingsService(settingsStore, events, clock)
    private val master = MemoryStockMaster()
    private val trading = FakeTradingPort()
    private val marketData = FakeMarketData()
    private val service =
        StartStockService(settings, master, trading, marketData, Duration.ofMillis(300))
    private val hynix = Symbol(Market.KR, "000660")

    @Test
    @DisplayName("직전에 보던 종목이 마스터에 살아 있으면 그것, 상장폐지면 보유 종목으로 넘어감")
    fun lastViewedWinsWhenListed() {
        settings.record(user, TradingFixtures.device, hynix, clock.instant())
        master.listed += hynix
        trading.holdings += holding(TradingFixtures.samsung, 10, "70000")

        assertThat(service.startStock(user))
            .isEqualTo(StartStock(hynix, StartStockReason.LAST_VIEWED))
        assertThat(events.replay(user, null, 0).map { it.payload }.toList())
            .containsExactly(
                UserSettingChanged(
                    "LAST_VIEWED_STOCK",
                    UserSettingCodec.lastViewedStock(settings.lastViewedStock(user)!!),
                )
            )

        master.listed.clear()
        assertThat(service.startStock(user))
            .isEqualTo(StartStock(TradingFixtures.samsung, StartStockReason.LARGEST_POSITION))
    }

    @Test
    @DisplayName("보유 종목은 평가금액 원화 환산이 가장 큰 것이며 미국은 현재 환율로 환산함")
    fun largestPositionInKrw() {
        trading.holdings += holding(TradingFixtures.samsung, 10, "70000")
        trading.holdings += holding(TradingFixtures.nvidia, 5, "120.00")
        marketData.rates[Currency.USD to Currency.KRW] =
            ExchangeRate(Currency.USD, Currency.KRW, BigDecimal("1400"), clock.instant())

        // 700,000원 vs 600 달러 × 1400 = 840,000원
        assertThat(service.startStock(user).symbol).isEqualTo(TradingFixtures.nvidia)
    }

    @Test
    @DisplayName("보유가 없거나 조회에 실패하면 기본 시장의 기본 종목이고, 기본 시장 설정을 따름")
    fun fallsBackToDefault() {
        assertThat(service.startStock(user))
            .isEqualTo(StartStock(Symbol.DEFAULT_KR, StartStockReason.DEFAULT))

        trading.accountFailure = BrokerUnavailableException("토스에 연결할 수 없음")
        settingsStore.rows[user to UserSettingKey.DEFAULT_MARKET] =
            UserSetting(
                user,
                UserSettingKey.DEFAULT_MARKET,
                UserSettingCodec.defaultMarket(Market.US),
                clock.instant(),
            )

        assertThat(service.startStock(user))
            .isEqualTo(StartStock(Symbol.DEFAULT_US, StartStockReason.DEFAULT))
    }

    @Test
    @DisplayName("직전 종목이 성립하면 보유·환율은 묻지 않음")
    fun lastViewedShortCircuits() {
        settings.record(user, TradingFixtures.device, hynix, clock.instant())
        master.listed += hynix
        trading.holdings += holding(TradingFixtures.samsung, 10, "70000")

        service.startStock(user)

        assertThat(trading.holdingsReads).isZero()
    }

    @Test
    @DisplayName("환율을 받지 못하면 미국 보유만 빼고 국내 보유 중 가장 큰 것을 고름")
    fun missingRateExcludesOnlyUsHoldings() {
        trading.holdings += holding(TradingFixtures.samsung, 10, "70000")
        trading.holdings += holding(TradingFixtures.nvidia, 50, "120.00")

        assertThat(service.startStock(user))
            .isEqualTo(StartStock(TradingFixtures.samsung, StartStockReason.LARGEST_POSITION))
    }

    @Test
    @DisplayName("직전 종목 후보는 설정 조회와 상장 확인을 합쳐 한 번의 제한 시간 안에 판단함")
    fun lastViewedCandidateHasOneTimeout() {
        settings.record(user, TradingFixtures.device, hynix, clock.instant())
        master.listed += hynix
        // 설정 조회와 상장 확인이 각각 제한 시간의 2/3 씩 걸림 → 합치면 한 후보의 제한을 넘김
        settingsStore.onFind = { Thread.sleep(200) }
        master.onIsListed = { Thread.sleep(200) }

        val started = System.nanoTime()
        val result = service.startStock(user)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertThat(result.reason).isEqualTo(StartStockReason.DEFAULT)
        assertThat(elapsedMillis).isLessThan(900)
    }

    @Test
    @DisplayName("보유 조회가 제한 시간 안에 끝나지 않으면 기다리지 않고 기본 종목으로 감")
    fun slowHoldingsAreSkipped() {
        trading.holdings += holding(TradingFixtures.samsung, 10, "70000")
        trading.onHoldingsRead = { Thread.sleep(2_000) }

        val started = System.nanoTime()
        val result = service.startStock(user)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertThat(result.reason).isEqualTo(StartStockReason.DEFAULT)
        assertThat(elapsedMillis).isLessThan(1_500)
    }

    private fun holding(symbol: Symbol, quantity: Long, price: String): BrokerHolding {
        val money = Money.of(price, symbol.market.currency)
        val shares = Quantity.of(quantity)
        return BrokerHolding(
            symbol,
            shares,
            money,
            money,
            money.times(shares),
            money.times(shares),
            Money.zero(symbol.market.currency),
        )
    }
}
