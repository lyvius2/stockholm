package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.trading.TradingFixtures.krw
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ChartTest {
    private val samsung = TradingFixtures.samsung

    @Nested
    @DisplayName("묶음 시작 시각")
    inner class BucketStart {
        @Test
        @DisplayName("분 단위는 시계에 맞춤: 09:09 는 09:00 묶음, 정확히 09:10 은 다음 묶음")
        fun minutesAlignToClock() {
            val ten = ChartResolution.MINUTE_10

            assertThat(ten.bucketStart(kst("2026-09-30T09:09:00")))
                .isEqualTo(kst("2026-09-30T09:00:00"))
            assertThat(ten.bucketStart(kst("2026-09-30T09:10:00")))
                .isEqualTo(kst("2026-09-30T09:10:00"))
            assertThat(ChartResolution.MINUTE_60.bucketStart(kst("2026-09-30T15:29:00")))
                .isEqualTo(kst("2026-09-30T15:00:00"))
            assertThat(ChartResolution.MINUTE_3.bucketStart(kst("2026-09-30T09:05:00")))
                .isEqualTo(kst("2026-09-30T09:03:00"))
        }

        @Test
        @DisplayName("주는 월요일, 월은 1일, 년은 1월 1일(한국 시간)에 시작함")
        fun calendarBuckets() {
            // 2026-09-30 은 수요일
            val day = kst("2026-09-30T00:00:00")

            assertThat(ChartResolution.DAY.bucketStart(day)).isEqualTo(day)
            assertThat(ChartResolution.WEEK.bucketStart(day)).isEqualTo(kst("2026-09-28T00:00:00"))
            assertThat(ChartResolution.WEEK.bucketStart(kst("2026-09-28T00:00:00")))
                .isEqualTo(kst("2026-09-28T00:00:00"))
            assertThat(ChartResolution.MONTH.bucketStart(day)).isEqualTo(kst("2026-09-01T00:00:00"))
            assertThat(ChartResolution.YEAR.bucketStart(day)).isEqualTo(kst("2026-01-01T00:00:00"))
        }
    }

    @Nested
    @DisplayName("봉 묶기")
    inner class RollUp {
        @Test
        @DisplayName("시가는 첫 봉, 종가는 마지막 봉, 고가·저가는 최고·최저, 거래량은 합이고 입력 순서와 무관함")
        fun aggregatesOhlcv() {
            val candles =
                listOf(
                    minute("09:02", open = 102, high = 110, low = 101, close = 105, volume = 30),
                    minute("09:00", open = 100, high = 103, low = 99, close = 101, volume = 10),
                    minute("09:01", open = 101, high = 102, low = 95, close = 102, volume = 20),
                    minute("09:03", open = 105, high = 106, low = 104, close = 104, volume = 5),
                )

            val bars = CandleRollup.rollUp(candles, ChartResolution.MINUTE_3)

            assertThat(bars)
                .containsExactly(
                    bar("09:00", open = 100, high = 110, low = 95, close = 105, volume = 60),
                    bar("09:03", open = 105, high = 106, low = 104, close = 104, volume = 5),
                )
        }

        @Test
        @DisplayName("60분봉은 세션을 구분하지 않아 미국 프리마켓 09:00~09:29 와 정규장 09:30~09:59 가 한 봉에 섞임")
        fun hourBarMixesSessionsAtUsOpen() {
            // 뉴욕 09:29(프리마켓 마지막 봉)와 09:30(정규장 첫 봉), 서머타임 -04:00
            val preMarket = usMinute("2026-09-30T09:29:00-04:00", price = "100.00", volume = 5)
            val regular = usMinute("2026-09-30T09:30:00-04:00", price = "101.00", volume = 900)

            val bars = CandleRollup.rollUp(listOf(preMarket, regular), ChartResolution.MINUTE_60)

            assertThat(bars).hasSize(1)
            assertThat(bars.single().openTime)
                .isEqualTo(OffsetDateTime.parse("2026-09-30T09:00:00-04:00").toInstant())
            assertThat(bars.single().open).isEqualTo(TradingFixtures.usd("100.00"))
            assertThat(bars.single().close).isEqualTo(TradingFixtures.usd("101.00"))
            assertThat(bars.single().volume).isEqualTo(Quantity.of(905))
            // 30분봉은 09:30 이 묶음 경계라 섞이지 않음
            assertThat(CandleRollup.rollUp(listOf(preMarket, regular), ChartResolution.MINUTE_30))
                .hasSize(2)
        }

        @Test
        @DisplayName("세션 시작을 알면 60분봉을 거기서 나눔: 미국 프리 09:00~09:29 와 정규장 09:30~ 이 다른 봉")
        fun splitsHourBarAtSessionStart() {
            val regularOpen = OffsetDateTime.parse("2026-09-30T09:30:00-04:00").toInstant()
            val preMarket = usMinute("2026-09-30T09:29:00-04:00", price = "100.00", volume = 5)
            val regular = usMinute("2026-09-30T09:30:00-04:00", price = "101.00", volume = 900)
            val later = usMinute("2026-09-30T10:00:00-04:00", price = "102.00", volume = 1)
            val sessions = SessionStartLookup {
                if (it.isBefore(regularOpen))
                    OffsetDateTime.parse("2026-09-30T04:00:00-04:00").toInstant()
                else regularOpen
            }

            val bars =
                CandleRollup.rollUp(
                    listOf(preMarket, regular, later),
                    ChartResolution.MINUTE_60,
                    sessions,
                )

            assertThat(bars.map { it.openTime })
                .containsExactly(
                    OffsetDateTime.parse("2026-09-30T09:00:00-04:00").toInstant(),
                    regularOpen,
                    OffsetDateTime.parse("2026-09-30T10:00:00-04:00").toInstant(),
                )
            assertThat(bars[1].volume).isEqualTo(Quantity.of(900))
            // 세션 시작이 시계 묶음 시작과 같거나 앞서면 시계 묶음 그대로
            assertThat(
                    ChartResolution.MINUTE_60.bucketStart(
                        regularOpen.plusSeconds(60 * 15),
                        regularOpen,
                    )
                )
                .isEqualTo(regularOpen)
            assertThat(
                    ChartResolution.MINUTE_60.bucketStart(
                        OffsetDateTime.parse("2026-09-30T10:20:00-04:00").toInstant(),
                        regularOpen,
                    )
                )
                .isEqualTo(OffsetDateTime.parse("2026-09-30T10:00:00-04:00").toInstant())
        }

        @Test
        @DisplayName("봉이 없는 구간은 묶음을 만들지 않고, 같은 시각의 봉이 겹쳐 와도 한 번만 셈")
        fun skipsGapsAndDuplicates() {
            val first = minute("09:00", 100, 100, 100, 100, 10)
            val later = minute("09:31", 200, 200, 200, 200, 1)

            val bars = CandleRollup.rollUp(listOf(first, first, later), ChartResolution.MINUTE_10)

            assertThat(bars.map { it.openTime })
                .containsExactly(kst("2026-09-30T09:00:00"), kst("2026-09-30T09:30:00"))
            assertThat(bars.first().volume).isEqualTo(Quantity.of(10))
        }

        @Test
        @DisplayName("분 단위를 일봉으로, 주 단위를 1분봉으로 만들지 않음. 다른 종목이 섞여도 거부함")
        fun rejectsWrongBaseOrMixedSymbols() {
            val minuteCandle = minute("09:00", 1, 1, 1, 1, 1)

            assertThatThrownBy { CandleRollup.rollUp(listOf(minuteCandle), ChartResolution.WEEK) }
                .isInstanceOf(InvalidValueException::class.java)
            assertThatThrownBy {
                    CandleRollup.rollUp(
                        listOf(minuteCandle, minuteCandle.copy(symbol = TradingFixtures.nvidia)),
                        ChartResolution.MINUTE_3,
                    )
                }
                .isInstanceOf(InvalidValueException::class.java)
            assertThat(CandleRollup.rollUp(emptyList(), ChartResolution.DAY)).isEmpty()
        }
    }

    @Nested
    @DisplayName("이동평균")
    inner class Averages {
        private val bars =
            listOf(100, 200, 301, 400).mapIndexed { index, close ->
                bar("09:0$index", close, close, close, close, volume = (index + 1) * 10)
            }

        @Test
        @DisplayName("기간이 안 찬 앞자리는 비고, 정확히 기간만큼 찬 자리부터 값이 있음")
        fun blankUntilWindowIsFull() {
            val averages = MovingAverage.ofCloses(bars, 3)

            // (100+200+301)/3 = 200.33 → 200, (200+301+400)/3 = 300.33 → 300
            assertThat(averages).containsExactly(null, null, krw("200"), krw("300"))
        }

        @Test
        @DisplayName("종가 평균의 반올림은 금액 규칙(HALF_EVEN)을 따름")
        fun roundsHalfEven() {
            val half = listOf(100, 101).map { bar("09:00", it, it, it, it, 1) }
            val next = listOf(101, 102).map { bar("09:00", it, it, it, it, 1) }

            // 100.5 → 100, 101.5 → 102
            assertThat(MovingAverage.ofCloses(half, 2).last()).isEqualTo(krw("100"))
            assertThat(MovingAverage.ofCloses(next, 2).last()).isEqualTo(krw("102"))
        }

        @Test
        @DisplayName("거래량 평균은 소수 둘째 자리까지 구함")
        fun volumeAverage() {
            assertThat(MovingAverage.ofVolumes(bars, 3))
                .containsExactly(null, null, BigDecimal("20.00"), BigDecimal("30.00"))
            assertThat(MovingAverage.ofVolumes(bars)).containsOnlyNulls()
        }

        @Test
        @DisplayName("기간이 0 이하면 거부함")
        fun rejectsNonPositiveWindow() {
            assertThatThrownBy { MovingAverage.ofCloses(bars, 0) }
                .isInstanceOf(InvalidValueException::class.java)
        }
    }

    private fun minute(time: String, open: Int, high: Int, low: Int, close: Int, volume: Int) =
        Candle(
            samsung,
            CandleInterval.MINUTE_1,
            kst("2026-09-30T$time:00"),
            krw("$open"),
            krw("$high"),
            krw("$low"),
            krw("$close"),
            Quantity.of(volume.toLong()),
        )

    private fun usMinute(at: String, price: String, volume: Long): Candle {
        val money = TradingFixtures.usd(price)
        return Candle(
            TradingFixtures.nvidia,
            CandleInterval.MINUTE_1,
            OffsetDateTime.parse(at).toInstant(),
            money,
            money,
            money,
            money,
            Quantity.of(volume),
        )
    }

    private fun bar(time: String, open: Int, high: Int, low: Int, close: Int, volume: Int) =
        ChartBar(
            samsung,
            ChartResolution.MINUTE_3,
            kst("2026-09-30T$time:00"),
            krw("$open"),
            krw("$high"),
            krw("$low"),
            krw("$close"),
            Quantity.of(volume.toLong()),
        )

    private fun kst(text: String): Instant = OffsetDateTime.parse("$text+09:00").toInstant()
}
