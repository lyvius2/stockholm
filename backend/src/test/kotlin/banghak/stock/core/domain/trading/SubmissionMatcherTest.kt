package banghak.stock.core.domain.trading

import banghak.stock.core.domain.trading.SubmissionMatcher.Match
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SubmissionMatcherTest {
    private val intent = TradingFixtures.limitBuy()
    private val submission =
        OrderSubmission(intent, ClientOrderId("REQ-1"), isHighValueConfirmed = false)
    private val sentAt = TradingFixtures.now

    @Test
    @DisplayName("보낸 뒤 접수된 같은 종목·방향·유형·가격·수량·유효 조건의 주문이 한 건이면 그 주문임")
    fun findsSingleMatchingOrder() {
        val ours = record("B-1")
        val otherPrice = record("B-2", intent.copy(limitPrice = TradingFixtures.krw("70100")))
        val otherSide = record("B-3", intent.copy(side = OrderSide.SELL))

        assertThat(match(listOf(ours, otherPrice, otherSide))).isEqualTo(Match.Found(ours))
    }

    @Test
    @DisplayName("금액 자릿수만 다른 값은 같은 가격으로 봄")
    fun comparesAmountsByValue() {
        val scaled = record("B-1").copy(limitPrice = TradingFixtures.krw("70000.00"))

        assertThat(match(listOf(scaled))).isEqualTo(Match.Found(scaled))
    }

    @Test
    @DisplayName("보내기 전에 접수된 주문은 아님. 단 시계 차이 10초까지는 봐 줌")
    fun ignoresOrdersPlacedBeforeSending() {
        val justBefore = record("B-1", orderedAt = sentAt.minusSeconds(10))
        val tooEarly = record("B-2", orderedAt = sentAt.minusSeconds(11))

        assertThat(match(listOf(justBefore))).isEqualTo(Match.Found(justBefore))
        assertThat(match(listOf(tooEarly))).isEqualTo(Match.NotFound)
    }

    @Test
    @DisplayName("이미 다른 제출에 연결된 주문은 후보에서 뺌")
    fun skipsClaimedOrders() {
        assertThat(match(listOf(record("B-1")), claimed = setOf("B-1"))).isEqualTo(Match.NotFound)
    }

    @Test
    @DisplayName("맞는 주문이 여러 건이면 어느 것인지 정하지 않음")
    fun reportsAmbiguity() {
        assertThat(match(listOf(record("B-1"), record("B-2"))))
            .isEqualTo(Match.Ambiguous(listOf("B-1", "B-2")))
    }

    private fun match(
        candidates: List<BrokerOrderRecord>,
        claimed: Set<String> = emptySet(),
    ): Match = SubmissionMatcher.match(submission, sentAt, candidates, claimed)

    private fun record(
        brokerOrderId: String,
        source: OrderIntent = intent,
        orderedAt: java.time.Instant = sentAt.plus(Duration.ofSeconds(1)),
    ) =
        TradingFixtures.brokerRecord(intent = source, brokerOrderId = brokerOrderId)
            .copy(orderedAt = orderedAt)
}
