package banghak.stock.core.domain.trading

import banghak.stock.core.domain.trading.ConditionalSubmissionMatcher.Match
import banghak.stock.core.domain.trading.TradingFixtures.krw
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ConditionalSubmissionTest {
    private val sentAt = TradingFixtures.now
    private val submission =
        ConditionalOrderSubmission(ConditionalFixtures.oco(), ClientOrderId("KEY-1"), false)

    @Nested
    @DisplayName("결과 모름 등록 찾기")
    inner class Matching {
        @Test
        @DisplayName("보낸 뒤 등록된 같은 모양의 조건주문 한 건을 찾음")
        fun findsOne() {
            val match =
                ConditionalSubmissionMatcher.match(
                    submission,
                    sentAt,
                    listOf(ConditionalFixtures.record(createdAt = sentAt.plusSeconds(1))),
                    emptySet(),
                )

            assertThat(match)
                .isEqualTo(
                    Match.Found(ConditionalFixtures.record(createdAt = sentAt.plusSeconds(1)))
                )
        }

        @Test
        @DisplayName("시계 차이 10초보다 먼저 등록된 것과 이미 연결된 번호는 후보가 아님")
        fun ignoresOlderAndClaimed() {
            val older =
                ConditionalFixtures.record(
                    conditionalOrderId = "OLD",
                    createdAt = sentAt.minusSeconds(11),
                )
            val claimed = ConditionalFixtures.record(conditionalOrderId = "MINE")

            val match =
                ConditionalSubmissionMatcher.match(
                    submission,
                    sentAt,
                    listOf(older, claimed),
                    setOf("MINE"),
                )

            assertThat(match).isEqualTo(Match.NotFound)
        }

        @Test
        @DisplayName("감시가가 다르면 다른 조건주문임")
        fun differentTriggerIsNotAMatch() {
            val other =
                ConditionalFixtures.record(ConditionalFixtures.oco(takeProfit = krw("81000")))

            assertThat(
                    ConditionalSubmissionMatcher.match(
                        submission,
                        sentAt,
                        listOf(other),
                        emptySet(),
                    )
                )
                .isEqualTo(Match.NotFound)
        }

        @Test
        @DisplayName("SINGLE 은 조회에 방향이 없어 같은 모양 한 건도 확정하지 않음")
        fun singleIsUnverifiable() {
            val single = ConditionalFixtures.single(side = OrderSide.BUY)
            val request = ConditionalOrderSubmission(single, ClientOrderId("KEY-2"), false)
            // 토스 응답에는 방향이 없어 앱에서 등록한 같은 가격·수량의 매도 SINGLE 과 구별되지 않음
            val sameShape = ConditionalFixtures.record(single, "APP-1")

            assertThat(
                    ConditionalSubmissionMatcher.match(
                        request,
                        sentAt,
                        listOf(sameShape),
                        emptySet(),
                    )
                )
                .isEqualTo(Match.DirectionUnverifiable(listOf("APP-1")))
            assertThat(ConditionalSubmissionMatcher.match(request, sentAt, emptyList(), emptySet()))
                .isEqualTo(Match.NotFound)
        }

        @Test
        @DisplayName("같은 모양이 둘이면 고르지 않음")
        fun twoMatchesAreAmbiguous() {
            val candidates =
                listOf(
                    ConditionalFixtures.record(conditionalOrderId = "A"),
                    ConditionalFixtures.record(conditionalOrderId = "B"),
                )

            assertThat(
                    ConditionalSubmissionMatcher.match(submission, sentAt, candidates, emptySet())
                )
                .isEqualTo(Match.Ambiguous(listOf("A", "B")))
        }
    }

    @Test
    @DisplayName("같은 키 요청은 금액 자릿수가 달라도 같은 내용이고, 수정 대상이 다르면 다른 요청임")
    fun sameRequestComparesValues() {
        val record =
            ConditionalSubmissionRecord(
                submission,
                SubmissionState.SENDING,
                null,
                null,
                sentAt,
                null,
            )
        val sameInOtherScale = ConditionalFixtures.oco(takeProfit = krw("80000.00"))

        assertThat(record.isSameRequestAs(sameInOtherScale, null)).isTrue()
        assertThat(record.isSameRequestAs(sameInOtherScale, "CO-9")).isFalse()
        assertThat(record.isSameRequestAs(ConditionalFixtures.oco(quantity = Quantity.of(9)), null))
            .isFalse()
    }
}
