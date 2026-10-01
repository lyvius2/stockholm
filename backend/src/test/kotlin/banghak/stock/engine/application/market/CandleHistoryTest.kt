package banghak.stock.engine.application.market

import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.trading.Candle
import banghak.stock.core.domain.trading.CandleCoverage
import banghak.stock.core.domain.trading.CandleInterval
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.FakeMarketData
import banghak.stock.support.fakes.MemoryCandleStore
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CandleHistoryTest {
    private val samsung = TradingFixtures.samsung
    private val t0 = Instant.parse("2026-09-30T00:00:00Z")
    private val clock = MutableClock(t0.plus(Duration.ofHours(10)))
    private val toss = FakeMarketData()
    private val store = MemoryCandleStore()
    private val history = CandleHistory(toss, store, clock)

    @Test
    @DisplayName("처음에는 토스에서 받아 저장하고, 이어지는 과거도 받아 보유 구간을 늘림")
    fun fetchesAndExtendsCoverage() {
        minutes(0 until 30)
        toss.candlePageSize = 10

        val newest = page(before = null)
        val older = page(before = newest.nextBefore)

        assertThat(newest.candles.map { it.openTime })
            .containsExactlyElementsOf(times(29 downTo 20))
        assertThat(newest.nextBefore).isEqualTo(at(19))
        assertThat(older.candles.map { it.openTime }).containsExactlyElementsOf(times(19 downTo 10))
        assertThat(store.coverage(samsung, M1))
            .isEqualTo(CandleCoverage(samsung, M1, at(10), at(29), reachedStart = false))
    }

    @Test
    @DisplayName("보유 구간 안의 과거는 토스를 부르지 않고 저장소에서 줌")
    fun servesCoveredRangeFromStore() {
        minutes(0 until 30)
        page(before = null)
        val readsAfterWarmUp = toss.candleReads

        val stored = page(before = at(15), count = 5)

        assertThat(toss.candleReads).isEqualTo(readsAfterWarmUp)
        assertThat(stored.candles.map { it.openTime })
            .containsExactlyElementsOf(times(15 downTo 11))
        assertThat(stored.nextBefore).isEqualTo(at(10))
    }

    @Test
    @DisplayName("가장 최근 쪽은 매번 다시 받아 진행 중이던 봉을 새 값으로 덮어씀")
    fun refreshesNewestPage() {
        minutes(0 until 5)
        page(before = null)
        toss.candles.removeIf { it.openTime == at(4) }
        toss.candles += candle(4, close = "99999")
        minutes(5 until 7)

        val refreshed = page(before = null)

        assertThat(refreshed.candles.first().openTime).isEqualTo(at(6))
        assertThat(refreshed.candles.first { it.openTime == at(4) }.close).isEqualTo(krw("99999"))
        assertThat(store.coverage(samsung, M1)?.to).isEqualTo(at(6))
    }

    @Test
    @DisplayName("오래 꺼져 있어 새로 받은 최근 봉이 보유 구간과 닿지 않으면 새 구간만 가진 것으로 둠")
    fun gapResetsCoverage() {
        minutes(0 until 5)
        page(before = null)
        minutes(100 until 110)
        toss.candlePageSize = 5

        val newest = page(before = null)

        assertThat(store.coverage(samsung, M1))
            .isEqualTo(CandleCoverage(samsung, M1, at(105), at(109), reachedStart = false))
        assertThat(newest.candles.map { it.openTime })
            .containsExactlyElementsOf(times(109 downTo 105))
        assertThat(newest.nextBefore).isEqualTo(at(104))
    }

    @Test
    @DisplayName("토스에 과거가 더 없으면 그 사실을 적어 두고 다시 묻지 않음")
    fun remembersStartOfHistory() {
        minutes(0 until 3)

        val whole = page(before = null)
        val reads = toss.candleReads
        val beyond = page(before = at(0).minus(Duration.ofMinutes(1)))

        assertThat(whole.nextBefore).isNull()
        assertThat(beyond.candles).isEmpty()
        assertThat(toss.candleReads).isEqualTo(reads)
    }

    @Test
    @DisplayName("토스를 받지 못하면 저장된 봉을 주고 지연으로 알리며, 저장된 것이 없으면 예외를 올림")
    fun fallsBackToStoredWhenTossIsDown() {
        toss.candleFailure = MarketDataUnavailableException("토스 시세에 연결할 수 없음")
        assertThatThrownBy { history.page(samsung, M1, null, 10) }
            .isInstanceOf(MarketDataUnavailableException::class.java)

        toss.candleFailure = null
        minutes(0 until 5)
        page(before = null)
        toss.candleFailure = MarketDataUnavailableException("토스 시세에 연결할 수 없음")

        val result = history.page(samsung, M1, null, 10)

        assertThat(result.isDelayed).isTrue()
        assertThat(result.page.candles).hasSize(5)
    }

    @Test
    @DisplayName("보존 기간이 지난 1분봉은 지우고 보유 구간을 줄이며, 정확히 90일 된 봉은 남김")
    fun purgesExpiredMinuteCandles() {
        minutes(0 until 3)
        page(before = null)
        // at(1) 이 정확히 90일 전이 되는 시각
        clock.moveTo(at(1).plus(CandleCoverage.MINUTE_RETENTION))

        val deleted = history.purgeExpired()

        assertThat(deleted).isEqualTo(1)
        assertThat(store.coverage(samsung, M1))
            .isEqualTo(CandleCoverage(samsung, M1, at(1), at(2), reachedStart = false))
    }

    private fun page(before: Instant?, count: Int = 100) =
        history.page(samsung, M1, before, count).page

    private fun minutes(range: IntRange) {
        range.forEach { toss.candles += candle(it, close = "70000") }
    }

    private fun candle(minute: Int, close: String): Candle {
        val price = krw(close)
        return Candle(samsung, M1, at(minute), price, price, price, price, Quantity.of(1))
    }

    private fun at(minute: Int): Instant = t0.plus(Duration.ofMinutes(minute.toLong()))

    private fun times(minutes: IntProgression): List<Instant> = minutes.map(::at)

    companion object {
        private val M1 = CandleInterval.MINUTE_1
    }
}
