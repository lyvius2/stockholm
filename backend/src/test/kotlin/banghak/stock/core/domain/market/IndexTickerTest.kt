package banghak.stock.core.domain.market

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.money.Percent
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class IndexTickerTest {
    // 2026-10-02(금) 한국 정규장 09:00~15:30 KST, 미국 정규장 09:30~16:00 ET(EDT = KST 22:30~05:00)
    private val krRegular = regular("2026-10-02T09:00:00+09:00", "2026-10-02T15:30:00+09:00")
    private val krNextRegular = regular("2026-10-05T09:00:00+09:00", "2026-10-05T15:30:00+09:00")
    private val usRegular = regular("2026-10-02T09:30:00-04:00", "2026-10-02T16:00:00-04:00")
    private val usPrevRegular = regular("2026-10-01T09:30:00-04:00", "2026-10-01T16:00:00-04:00")

    @Nested
    @DisplayName("세트 선택")
    inner class SetChoice {
        @Test
        @DisplayName("한국 정규장 중에는 한국 세트, 정확히 15:30 에는 장 밖")
        fun koreanRegular() {
            assertThat(choose("2026-10-02T09:00:00+09:00"))
                .isEqualTo(IndexSetChoice(Market.KR, IndexSetState.OPEN))
            assertThat(choose("2026-10-02T15:29:59+09:00").state).isEqualTo(IndexSetState.OPEN)
            assertThat(choose("2026-10-02T15:30:00+09:00").state).isEqualTo(IndexSetState.CLOSE)
        }

        @Test
        @DisplayName("미국 정규장 중에는 미국 세트, 개장 정확히 30분 전부터 미국 프리")
        fun usRegularAndPre() {
            assertThat(choose("2026-10-02T22:30:00+09:00"))
                .isEqualTo(IndexSetChoice(Market.US, IndexSetState.OPEN))
            assertThat(choose("2026-10-02T22:00:00+09:00"))
                .isEqualTo(IndexSetChoice(Market.US, IndexSetState.PRE))
            assertThat(choose("2026-10-02T21:59:59+09:00"))
                .isEqualTo(IndexSetChoice(Market.KR, IndexSetState.CLOSE))
        }

        @Test
        @DisplayName("두 장이 닫혀 있으면 더 늦게 닫힌 시장의 종가, 한국 개장 30분 전부터는 한국 종가")
        fun closedPeriods() {
            // 토요일 아침: 미국이 05:00 KST 에 더 늦게 닫힘
            assertThat(choose("2026-10-03T10:00:00+09:00"))
                .isEqualTo(IndexSetChoice(Market.US, IndexSetState.CLOSE))
            // 월요일 08:30: 한국 개장 30분 전
            assertThat(choose("2026-10-05T08:30:00+09:00"))
                .isEqualTo(IndexSetChoice(Market.KR, IndexSetState.CLOSE))
            // 달력이 하나도 없으면 한국 종가
            assertThat(
                    IndexSetRule.choose(at("2026-10-03T10:00:00+09:00"), emptyList(), emptyList())
                )
                .isEqualTo(IndexSetChoice(Market.KR, IndexSetState.CLOSE))
        }

        @Test
        @DisplayName("정규장 창만 받음")
        fun regularOnly() {
            val pre =
                SessionWindow(
                    MarketSession.PRE,
                    at("2026-10-02T08:00:00+09:00"),
                    at("2026-10-02T09:00:00+09:00"),
                )
            assertThatThrownBy {
                    IndexSetRule.choose(at("2026-10-02T08:30:00+09:00"), listOf(pre), emptyList())
                }
                .isInstanceOf(InvalidValueException::class.java)
        }

        private fun choose(text: String) =
            IndexSetRule.choose(
                at(text),
                listOf(krRegular, krNextRegular),
                listOf(usPrevRegular, usRegular),
            )
    }

    @Test
    @DisplayName("등락은 전일 종가 대비이고 전일 종가가 없거나 0이면 비움")
    fun changeAgainstPreviousClose() {
        val now = at("2026-10-02T10:00:00+09:00")

        val quote =
            IndexQuote.of(
                IndexCode.KOSPI,
                BigDecimal("3412.85"),
                BigDecimal("3400.55"),
                now,
                false,
                null,
                "TOSS",
            )

        assertThat(quote.change).isEqualByComparingTo("12.30")
        assertThat(quote.changeRatio).isEqualTo(Percent.ofRatio("0.003617"))
        assertThat(
                IndexQuote.of(IndexCode.KOSPI, BigDecimal.TEN, null, now, false, null, "TOSS")
                    .changeRatio
            )
            .isNull()
        assertThat(
                IndexQuote.of(
                        IndexCode.KOSPI,
                        BigDecimal.TEN,
                        BigDecimal.ZERO,
                        now,
                        false,
                        null,
                        "TOSS",
                    )
                    .changeRatio
            )
            .isNull()
        assertThat(IndexCode.setOf(Market.KR))
            .containsExactly(IndexCode.KOSPI, IndexCode.KOSDAQ, IndexCode.NIKKEI225)
    }

    @Test
    @DisplayName("티커 자리는 세트 순서와 같아야 하고, 지연은 DELAYED 자리가 있을 때만임(출처 미설정은 지연이 아님)")
    fun entriesFollowTheSet() {
        val choice = IndexSetChoice(Market.KR, IndexSetState.CLOSE)
        val entries =
            listOf(
                IndexEntry(IndexCode.KOSPI, null, IndexSourceState.FRESH),
                IndexEntry(IndexCode.KOSDAQ, null, IndexSourceState.FRESH),
                IndexEntry(IndexCode.NIKKEI225, null, IndexSourceState.UNCONFIGURED),
            )

        assertThat(IndexTicker(choice, entries, Instant.EPOCH).isDelayed).isFalse()
        assertThat(
                IndexTicker(
                        choice,
                        entries.map { it.copy(state = IndexSourceState.DELAYED) },
                        Instant.EPOCH,
                    )
                    .isDelayed
            )
            .isTrue()
        assertThatThrownBy { IndexTicker(choice, entries.reversed(), Instant.EPOCH) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    private fun regular(start: String, end: String) =
        SessionWindow(MarketSession.REGULAR, at(start), at(end))

    private fun at(text: String): Instant = OffsetDateTime.parse(text).toInstant()
}
