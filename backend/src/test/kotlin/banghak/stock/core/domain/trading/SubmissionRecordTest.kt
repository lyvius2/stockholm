package banghak.stock.core.domain.trading

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SubmissionRecordTest {
    private val intent = TradingFixtures.limitBuy()
    private val key = ClientOrderId("REQ-1")

    @Test
    @DisplayName("같은 키의 새 주문은 종목·방향·유형·가격·수량이 같을 때만 같은 요청이고, 자릿수 차이는 같게 봄")
    fun sameOrderComparesContent() {
        val stored = record(intent, replaces = null)

        assertThat(stored.isSameOrderAs(intent.copy(limitPrice = TradingFixtures.krw("70000.00"))))
            .isTrue()
        assertThat(stored.isSameOrderAs(intent.copy(limitPrice = TradingFixtures.krw("70100"))))
            .isFalse()
        assertThat(stored.isSameOrderAs(intent.copy(quantity = Quantity.of(11)))).isFalse()
        assertThat(stored.isSameOrderAs(intent.copy(side = OrderSide.SELL))).isFalse()
    }

    @Test
    @DisplayName("같은 키의 정정은 원주문과 준 가격·수량이 같을 때만 같은 요청임")
    fun sameAmendmentComparesContent() {
        val stored =
            record(intent.copy(limitPrice = TradingFixtures.krw("71000")), replaces = "B-1")

        assertThat(
                stored.isSameAmendmentAs("B-1", OrderAmendment(TradingFixtures.krw("71000"), null))
            )
            .isTrue()
        assertThat(
                stored.isSameAmendmentAs("B-2", OrderAmendment(TradingFixtures.krw("71000"), null))
            )
            .isFalse()
        assertThat(
                stored.isSameAmendmentAs("B-1", OrderAmendment(TradingFixtures.krw("72000"), null))
            )
            .isFalse()
        assertThat(stored.isSameAmendmentAs("B-1", OrderAmendment(null, Quantity.of(5)))).isFalse()
        assertThat(stored.isSameOrderAs(stored.submission.intent))
            .describedAs("정정 기록은 새 주문과 다름")
            .isFalse()
    }

    private fun record(source: OrderIntent, replaces: String?) =
        SubmissionRecord(
            OrderSubmission(source, key, isHighValueConfirmed = false),
            TradingFixtures.device,
            SubmissionState.ACCEPTED,
            "A-1",
            null,
            TradingFixtures.now,
            replaces,
        )
}
