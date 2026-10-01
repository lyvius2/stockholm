package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.trading.TradingFixtures.krw
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class LiveCandleTest {
    private val samsung = TradingFixtures.samsung
    private val minute = Instant.parse("2026-09-30T00:00:00Z")

    @Test
    @DisplayName("첫 체결이 시가·고가·저가·종가가 되고 봉은 그 분의 시작 시각에 열림")
    fun startsFromFirstTick() {
        val live = LiveCandle.start(tick(17, "70000", 3))

        assertThat(live.candle)
            .isEqualTo(candle(open = "70000", high = "70000", low = "70000", close = "70000", 3))
    }

    @Test
    @DisplayName("같은 분의 체결은 고가·저가·종가·거래량에 합치고 닫힌 봉은 없음")
    fun mergesTicksInSameMinute() {
        val step =
            LiveCandle.start(tick(1, "70000", 3))
                .accept(tick(20, "70500", 2))
                .current
                .accept(tick(59, "69800", 5))

        assertThat(step.closed).isNull()
        assertThat(step.current.candle)
            .isEqualTo(candle(open = "70000", high = "70500", low = "69800", close = "69800", 10))
    }

    @Test
    @DisplayName("정확히 다음 분 00초의 체결은 직전 봉을 닫고 새 봉을 시작함")
    fun nextMinuteClosesBar() {
        val first = LiveCandle.start(tick(30, "70000", 3))

        val step = first.accept(tick(60, "70100", 1))

        assertThat(step.closed).isEqualTo(first.candle)
        assertThat(step.current.candle.openTime).isEqualTo(minute.plusSeconds(60))
        assertThat(step.current.candle.open).isEqualTo(krw("70100"))
        assertThat(step.current.candle.volume).isEqualTo(Quantity.of(1))
    }

    @Test
    @DisplayName("이미 지난 분의 늦은 체결은 버리고, 다른 종목의 체결은 거부함")
    fun ignoresLateTicksAndRejectsOtherSymbols() {
        val live = LiveCandle.start(tick(90, "70000", 3))

        val late = live.accept(tick(59, "99999", 100))

        assertThat(late.current).isEqualTo(live)
        assertThat(late.closed).isNull()
        assertThatThrownBy {
                live.accept(
                    TradeTick(
                        TradingFixtures.nvidia,
                        TradingFixtures.usd("100"),
                        Quantity.of(1),
                        minute,
                    )
                )
            }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("그 분이 지나면 진행 중인 봉이 아님(분의 마지막 순간까지는 진행 중)")
    fun isCurrentOnlyWithinItsMinute() {
        val live = LiveCandle.start(tick(10, "70000", 1))

        assertThat(live.isCurrentAt(minute.plusSeconds(59).plusMillis(999))).isTrue()
        assertThat(live.isCurrentAt(minute.plusSeconds(60))).isFalse()
    }

    private fun tick(second: Long, price: String, volume: Long) =
        TradeTick(samsung, krw(price), Quantity.of(volume), minute.plusSeconds(second))

    private fun candle(open: String, high: String, low: String, close: String, volume: Long) =
        Candle(
            samsung,
            CandleInterval.MINUTE_1,
            minute,
            krw(open),
            krw(high),
            krw(low),
            krw(close),
            Quantity.of(volume),
        )
}
