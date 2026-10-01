package banghak.stock.engine.application.market

import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradeTick
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.FakeRealtimeFeed
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class LiveCandleServiceTest {
    private val samsung = TradingFixtures.samsung
    private val minute = Instant.parse("2026-09-30T00:00:00Z")
    private val clock = MutableClock(minute.plusSeconds(30))
    private val feed = FakeRealtimeFeed()
    private val service = LiveCandleService(feed, clock).also { it.listen() }

    @Test
    @DisplayName("실시간 체결을 받아 종목별 진행 중인 봉을 만들고, 체결이 없는 종목은 봉이 없음")
    fun buildsLiveCandlePerSymbol() {
        assertThat(feed.listeners).containsExactly(service)

        service.onTrade(tick(5, "70000", 3))
        service.onTrade(tick(20, "70200", 2))

        val candle = service.liveCandle(samsung)
        assertThat(candle?.open).isEqualTo(krw("70000"))
        assertThat(candle?.close).isEqualTo(krw("70200"))
        assertThat(candle?.volume).isEqualTo(Quantity.of(5))
        assertThat(service.liveCandle(TradingFixtures.nvidia)).isNull()
    }

    @Test
    @DisplayName("분이 넘어간 체결은 새 봉이 되고, 체결이 끊겨 분이 지난 봉은 진행 중으로 주지 않음")
    fun rollsOverAndHidesStaleCandle() {
        service.onTrade(tick(5, "70000", 3))
        service.onTrade(tick(61, "70300", 1))
        clock.advance(Duration.ofSeconds(60))

        assertThat(service.liveCandle(samsung)?.openTime).isEqualTo(minute.plusSeconds(60))
        assertThat(service.liveCandle(samsung)?.open).isEqualTo(krw("70300"))

        clock.advance(Duration.ofSeconds(60))
        assertThat(service.liveCandle(samsung)).isNull()
    }

    private fun tick(second: Long, price: String, volume: Long) =
        TradeTick(samsung, krw(price), Quantity.of(volume), minute.plusSeconds(second))
}
