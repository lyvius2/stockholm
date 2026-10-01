package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.trading.TradingFixtures.krw
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CandleCoverageTest {
    private val samsung = TradingFixtures.samsung
    private val t0 = Instant.parse("2026-09-30T00:00:00Z")
    private val coverage = CandleCoverage(samsung, CandleInterval.MINUTE_1, at(10), at(20), false)

    @Test
    @DisplayName("구간은 양 끝을 포함하고, 시작이 끝보다 늦을 수 없음")
    fun containsBoundaries() {
        assertThat(coverage.contains(at(10))).isTrue()
        assertThat(coverage.contains(at(20))).isTrue()
        assertThat(coverage.contains(at(9))).isFalse()
        assertThat(coverage.contains(at(21))).isFalse()
        assertThatThrownBy { coverage.copy(from = at(21)) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("과거를 이어 받을 위치는 시작 바로 앞 봉이고, 과거가 더 없으면 없음")
    fun olderCursor() {
        assertThat(coverage.olderCursor()).isEqualTo(at(9))
        assertThat(coverage.copy(reachedStart = true).olderCursor()).isNull()
    }

    @Test
    @DisplayName("새로 받은 최근 페이지가 구간 바로 다음 봉부터면 이어 늘리고, 한 봉이라도 비면 새 페이지만 남김")
    fun mergeNewestNeedsContact() {
        val adjacent = coverage.mergeNewest(page(21..25, nextBefore = at(20)))
        val gapped = coverage.mergeNewest(page(22..25, nextBefore = at(21)))

        assertThat(adjacent).isEqualTo(coverage.copy(to = at(25)))
        assertThat(gapped)
            .isEqualTo(CandleCoverage(samsung, CandleInterval.MINUTE_1, at(22), at(25), false))
    }

    @Test
    @DisplayName("새 페이지가 겹치면 합치고, 증권사 과거 전체면 시작까지 받은 것으로 둠")
    fun mergeNewestOverlapAndWholeHistory() {
        assertThat(coverage.mergeNewest(page(18..23, nextBefore = at(17))))
            .isEqualTo(coverage.copy(to = at(23)))
        assertThat(coverage.mergeNewest(page(30..31, nextBefore = null)))
            .isEqualTo(coverage.copy(to = at(31), reachedStart = true))
        assertThat(coverage.mergeNewest(CandlePage(emptyList(), null))).isEqualTo(coverage)
    }

    @Test
    @DisplayName("과거 페이지를 합치면 시작이 앞당겨지고, 빈 페이지나 마지막 페이지면 과거가 더 없음")
    fun mergeOlder() {
        assertThat(coverage.mergeOlder(page(5..9, nextBefore = at(4))))
            .isEqualTo(coverage.copy(from = at(5)))
        assertThat(coverage.mergeOlder(page(5..9, nextBefore = null)))
            .isEqualTo(coverage.copy(from = at(5), reachedStart = true))
        assertThat(coverage.mergeOlder(CandlePage(emptyList(), null)))
            .isEqualTo(coverage.copy(reachedStart = true))
    }

    @Test
    @DisplayName("보존 기간으로 앞쪽을 지우면 시작이 그 시각이 되고 과거는 다시 받을 수 있는 것으로 둠")
    fun trimmedTo() {
        val whole = coverage.copy(reachedStart = true)

        assertThat(whole.trimmedTo(at(15))).isEqualTo(coverage.copy(from = at(15)))
        assertThat(whole.trimmedTo(at(10))).isEqualTo(whole)
        assertThat(whole.trimmedTo(at(20))).isEqualTo(coverage.copy(from = at(20)))
        assertThat(whole.trimmedTo(at(21))).isNull()
    }

    private fun at(minute: Int): Instant = t0.plus(Duration.ofMinutes(minute.toLong()))

    private fun page(minutes: IntRange, nextBefore: Instant?): CandlePage =
        CandlePage(
            minutes.reversed().map {
                val price = krw("70000")
                Candle(
                    samsung,
                    CandleInterval.MINUTE_1,
                    at(it),
                    price,
                    price,
                    price,
                    price,
                    Quantity.of(1),
                )
            },
            nextBefore,
        )
}
