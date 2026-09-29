package banghak.stock.core.domain.trading

import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FillIncrementTest {
    private val user = TradingFixtures.user
    private val now = Instant.parse("2026-09-30T01:00:00Z")
    private val nothingQueued = FillSummary.zero(Currency.KRW)

    @Test
    @DisplayName("아직 넣은 것이 없으면 누적 체결 전체가 증분임")
    fun wholeFillWhenNothingQueued() {
        val fill =
            FillIncrement.between(
                user,
                nothingQueued,
                filled(10, "700000", "105"),
                OrderOrigin.MANUAL,
                now,
            )!!

        assertThat(fill.quantity).isEqualTo(Quantity.of(10))
        assertThat(fill.amount).isEqualTo(krw("700000"))
        assertThat(fill.fee).isEqualTo(krw("105"))
        assertThat(fill.unitPrice()).isEqualTo(krw("70000"))
    }

    @Test
    @DisplayName("이미 넣은 요약과의 차이만 증분이고, 단가는 금액 차이 ÷ 수량 차이임")
    fun differenceSinceQueued() {
        val queued = FillSummary(Quantity.of(4), krw("280000"), krw("42"), krw("0"))

        val fill =
            FillIncrement.between(
                user,
                queued,
                filled(10, "703000", "105"),
                OrderOrigin.AUTO_BUY,
                now,
            )!!

        assertThat(fill.quantity).isEqualTo(Quantity.of(6))
        assertThat(fill.amount).isEqualTo(krw("423000"))
        assertThat(fill.unitPrice()).isEqualTo(krw("70500"))
        assertThat(fill.fee).isEqualTo(krw("63"))
        assertThat(queued.plus(fill))
            .isEqualTo(FillSummary(Quantity.of(10), krw("703000"), krw("105"), krw("0")))
    }

    @Test
    @DisplayName("체결 수량이 넣은 것보다 늘지 않았으면 증분이 없음")
    fun noIncrementWithoutMoreShares() {
        val queued = FillSummary(Quantity.of(10), krw("700000"), krw("105"), krw("0"))

        assertThat(
                FillIncrement.between(
                    user,
                    queued,
                    filled(10, "700000", "105"),
                    OrderOrigin.MANUAL,
                    now,
                )
            )
            .isNull()
    }

    @Test
    @DisplayName("체결 금액이 아직 없으면 증분을 만들지 않고, 금액이 채워진 뒤에는 넣지 못한 수량 전체가 증분임")
    fun waitsForAmount() {
        assertThat(
                FillIncrement.between(
                    user,
                    nothingQueued,
                    filled(4, null, null),
                    OrderOrigin.MANUAL,
                    now,
                )
            )
            .isNull()

        val later =
            FillIncrement.between(
                user,
                nothingQueued,
                filled(10, "703000", null),
                OrderOrigin.MANUAL,
                now,
            )!!

        assertThat(later.quantity).isEqualTo(Quantity.of(10))
        assertThat(later.amount).isEqualTo(krw("703000"))
    }

    @Test
    @DisplayName("체결 시각이 있으면 그 시각을, 없으면(실시간 채널) 받은 시각을 씀")
    fun executionTime() {
        val filledAt = Instant.parse("2026-09-30T00:59:58Z")

        assertThat(
                FillIncrement.between(
                        user,
                        nothingQueued,
                        filled(10, "700000", "0").copy(filledAt = filledAt),
                        OrderOrigin.MANUAL,
                        now,
                    )!!
                    .executedAt
            )
            .isEqualTo(filledAt)
        assertThat(
                FillIncrement.between(
                        user,
                        nothingQueued,
                        filled(10, "700000", "0"),
                        OrderOrigin.MANUAL,
                        now,
                    )!!
                    .executedAt
            )
            .isEqualTo(now)
    }

    @Test
    @DisplayName("체결 금액이 없고 평균가가 있으면 평균가 × 수량으로 씀")
    fun amountFromAveragePrice() {
        val record = filled(10, null, null).copy(averageFilledPrice = krw("70000"))

        assertThat(
                FillIncrement.between(user, nothingQueued, record, OrderOrigin.MANUAL, now)!!.amount
            )
            .isEqualTo(krw("700000"))
    }

    private fun filled(qty: Long, amount: String?, fee: String?) =
        TradingFixtures.brokerRecord(status = OrderStatus.FILLED, filled = Quantity.of(qty))
            .copy(filledAmount = amount?.let(::krw), fee = fee?.let(::krw))

    private fun krw(amount: String): Money = TradingFixtures.krw(amount)
}
