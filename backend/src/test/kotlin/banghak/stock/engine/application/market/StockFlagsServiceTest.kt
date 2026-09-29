package banghak.stock.engine.application.market

import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.market.KrTradingDetail
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.StockWarning
import banghak.stock.core.domain.market.StockWarningType
import banghak.stock.core.domain.market.Symbol
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.FakeStockCatalog
import banghak.stock.support.fakes.MemoryStockFlagsCache
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class StockFlagsServiceTest {
    private val clock = MutableClock(Instant.parse("2026-09-29T01:00:00Z"))
    private val catalog = FakeStockCatalog()
    private val cache = MemoryStockFlagsCache()
    private val service = StockFlagsService(catalog, cache, clock)
    private val samsung = Symbol(Market.KR, "005930")
    private val nvidia = Symbol(Market.US, "NVDA")

    @Test
    @DisplayName("국내 종목은 유의사항과 종목 정보의 거래정지를 합쳐 캐시에 남김")
    fun fetchesWarningsAndHaltForKorea() {
        catalog.warnings[samsung] = listOf(StockWarning(StockWarningType.VI_DYNAMIC, null, null))
        catalog.krDetails[samsung] =
            KrTradingDetail(false, true, isKrxSuspended = true, isNxtSuspended = false)

        val flags = service.flags(samsung)

        assertThat(flags.isViDynamic).isTrue()
        assertThat(flags.isTradingHalted).isTrue()
        assertThat(cache.find(samsung)).isEqualTo(flags)
    }

    @Test
    @DisplayName("미국 종목은 종목 정보를 부르지 않고 거래정지는 모름으로 둠")
    fun usSkipsProfileLookup() {
        val flags = service.flags(nvidia)

        assertThat(catalog.profileCalls).isEmpty()
        assertThat(flags.isTradingHalted).isNull()
    }

    @Test
    @DisplayName("10초 안에는 캐시를 쓰고, 10초가 지나면 다시 받음")
    fun cacheLivesTenSeconds() {
        service.flags(samsung)
        clock.advance(Duration.ofMillis(9_999))
        service.flags(samsung)
        assertThat(catalog.warningCalls).hasSize(1)

        clock.advance(Duration.ofMillis(1))
        service.flags(samsung)

        assertThat(catalog.warningCalls).hasSize(2)
    }

    @Test
    @DisplayName("증권사에 닿지 않으면 지난 캐시를 돌려주고, 캐시도 없으면 실패함")
    fun fallsBackToStaleCache() {
        val stale = service.flags(samsung)
        clock.advance(Duration.ofMinutes(5))
        catalog.isUnavailable = true

        assertThat(service.flags(samsung)).isEqualTo(stale)
        assertThatThrownBy { service.flags(nvidia) }
            .isInstanceOf(MarketDataUnavailableException::class.java)
    }
}
