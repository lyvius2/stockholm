package banghak.stock.engine.application.market

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.trading.Candle
import banghak.stock.core.domain.trading.CandleInterval
import banghak.stock.core.domain.trading.ChartResolution
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.usecase.ChartQuery
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.FakeMarketData
import banghak.stock.support.fakes.MemoryCandleStore
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ChartServiceTest {
    private val samsung = TradingFixtures.samsung
    private val marketData = FakeMarketData()
    private val clock = MutableClock(Instant.parse("2026-10-10T00:00:00Z"))
    private val service = ChartService(CandleHistory(marketData, MemoryCandleStore(), clock))
    private val open = kst("2026-09-30T09:00:00")

    @Test
    @DisplayName("받을 과거가 더 없으면 가장 오래된 묶음까지 돌려주고 다음 위치가 없음")
    fun returnsEverythingWhenExhausted() {
        minutes(25)

        val chart = service.chart(query(ChartResolution.MINUTE_10, count = 10))

        assertThat(chart.bars.map { it.openTime })
            .containsExactly(open, open.plus(TEN), open.plus(TEN).plus(TEN))
        assertThat(chart.bars.last().volume).isEqualTo(Quantity.of(5))
        assertThat(chart.nextBefore).isNull()
    }

    @Test
    @DisplayName("요청한 수만큼 최근 봉을 주고, 다음 위치로 이어 받으면 빠지거나 겹치는 봉이 없음")
    fun pagesWithoutGapOrOverlap() {
        minutes(60)
        marketData.candlePageSize = 7

        val first = service.chart(query(ChartResolution.MINUTE_10, count = 2))
        val second =
            service.chart(query(ChartResolution.MINUTE_10, count = 10, before = first.nextBefore))

        assertThat(first.bars.map { it.openTime })
            .containsExactly(open.plus(Duration.ofMinutes(40)), open.plus(Duration.ofMinutes(50)))
        assertThat(first.nextBefore).isEqualTo(open.plus(Duration.ofMinutes(39)))
        assertThat(second.bars.map { it.openTime })
            .containsExactly(
                open,
                open.plus(TEN),
                open.plus(Duration.ofMinutes(20)),
                open.plus(Duration.ofMinutes(30)),
            )
        assertThat((second.bars + first.bars).map { it.volume }).containsOnly(Quantity.of(10))
        assertThat(second.nextBefore).isNull()
    }

    @Test
    @DisplayName("과거가 더 남았으면 덜 찬 가장 오래된 묶음은 주지 않음")
    fun dropsPartialOldestBucket() {
        minutes(60)
        // 한 쪽 25개: 09:35~09:59 → 09:30 묶음은 5개뿐이라 덜 참
        marketData.candlePageSize = 25

        val chart = service.chart(query(ChartResolution.MINUTE_10, count = 2))

        assertThat(chart.bars.map { it.openTime })
            .containsExactly(open.plus(Duration.ofMinutes(40)), open.plus(Duration.ofMinutes(50)))
        assertThat(marketData.candleReads).isEqualTo(1)
    }

    @Test
    @DisplayName("이동평균은 받은 봉 전체로 계산한 뒤 돌려줄 봉에 맞춰 자름")
    fun averagesUseAllFetchedBars() {
        minutes(10)

        val chart = service.chart(query(ChartResolution.MINUTE_1, count = 3))

        // 종가는 100, 101, … 109.
        // 마지막 5개 평균 = 107
        assertThat(chart.bars).hasSize(3)
        assertThat(chart.closeAverages.getValue(5))
            .containsExactly(krw("105"), krw("106"), krw("107"))
        assertThat(chart.closeAverages.getValue(20)).containsOnlyNulls().hasSize(3)
        assertThat(chart.volumeAverage).hasSize(3)
        assertThat(chart.nextBefore).isEqualTo(open.plus(Duration.ofMinutes(6)))
    }

    @Test
    @DisplayName("주봉은 일봉으로 만들고, 봉이 없으면 빈 차트임")
    fun weeklyFromDailyAndEmpty() {
        assertThat(service.chart(query(ChartResolution.WEEK, count = 5)).bars).isEmpty()

        // 2026-09-28(월) ~ 10-02(금), 10-05(월)
        listOf(28, 29, 30).forEach { day(kst("2026-09-${day(it)}T00:00:00")) }
        day(kst("2026-10-05T00:00:00"))

        val chart = service.chart(query(ChartResolution.WEEK, count = 5))

        assertThat(chart.bars.map { it.openTime })
            .containsExactly(kst("2026-09-28T00:00:00"), kst("2026-10-05T00:00:00"))
        assertThat(chart.bars.first().volume).isEqualTo(Quantity.of(3))
    }

    @Test
    @DisplayName("봉 수는 1~500 만 받음")
    fun countWithinRange() {
        assertThatThrownBy { query(ChartResolution.DAY, count = 0) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { query(ChartResolution.DAY, count = 501) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    // 09:00 부터 1분봉 n 개.
    // 종가는 100 부터 1씩 오르고 거래량은 1
    private fun minutes(n: Int) {
        repeat(n) { index ->
            val price = krw("${100 + index}")
            marketData.candles +=
                Candle(
                    samsung,
                    CandleInterval.MINUTE_1,
                    open.plus(Duration.ofMinutes(index.toLong())),
                    price,
                    price,
                    price,
                    price,
                    Quantity.of(1),
                )
        }
    }

    private fun day(at: Instant) {
        val price = krw("70000")
        marketData.candles +=
            Candle(samsung, CandleInterval.DAY_1, at, price, price, price, price, Quantity.of(1))
    }

    private fun day(day: Int): String = day.toString().padStart(2, '0')

    private fun query(resolution: ChartResolution, count: Int, before: Instant? = null) =
        ChartQuery(samsung, resolution, before, count)

    private fun kst(text: String): Instant = OffsetDateTime.parse("$text+09:00").toInstant()

    companion object {
        private val TEN: Duration = Duration.ofMinutes(10)
    }
}
