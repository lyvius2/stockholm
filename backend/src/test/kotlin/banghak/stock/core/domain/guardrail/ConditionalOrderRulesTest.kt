package banghak.stock.core.domain.guardrail

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.guardrail.GuardrailFinding.Clear
import banghak.stock.core.domain.guardrail.GuardrailFinding.Note
import banghak.stock.core.domain.guardrail.GuardrailFinding.Violation
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.trading.ConditionalFixtures
import banghak.stock.core.domain.trading.ConditionalOrderIntent
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.domain.trading.TradingFixtures.usd
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ConditionalOrderRulesTest {
    private val now: Instant = Instant.parse("2026-09-30T01:00:00Z")
    private val today: LocalDate = LocalDate.of(2026, 9, 30)

    @Nested
    @DisplayName("ConditionalExpiry")
    inner class Expiry {
        private val rule = ConditionalExpiry()

        @Test
        @DisplayName("오늘 만료는 받고 어제 만료는 거부함")
        fun todayIsTheLastAcceptedDay() {
            assertThat(rule.check(context(ConditionalFixtures.oco(expire = today))))
                .isEqualTo(Clear)
            assertThat(rule.check(context(ConditionalFixtures.oco(expire = today.minusDays(1)))))
                .isInstanceOf(Violation::class.java)
        }
    }

    @Nested
    @DisplayName("ConditionalHighValue")
    inner class HighValue {
        private val rule = ConditionalHighValue()

        @Test
        @DisplayName("가장 큰 조건 금액이 정확히 1억원이면 확인 노트, 30억원 초과면 거부")
        fun judgesTheLargestLeg() {
            // 1주 1억원 익절 조건, 손절 조건은 5천만원
            val exactlyHundredMillion =
                ConditionalFixtures.oco(
                    quantity = Quantity.of(1),
                    takeProfit = krw("100000000"),
                    stopLoss = krw("50000000"),
                )
            val overLimit =
                ConditionalFixtures.oco(
                    quantity = Quantity.of(1),
                    takeProfit = krw("3000000001"),
                    stopLoss = krw("50000000"),
                )

            assertThat(rule.check(context(exactlyHundredMillion)))
                .isEqualTo(Note(HighValueOrder.NAME, "1억원 이상 고액 주문. 확인이 필요함"))
            assertThat(rule.check(context(overLimit))).isInstanceOf(Violation::class.java)
            assertThat(rule.check(context(ConditionalFixtures.oco()))).isEqualTo(Clear)
        }

        @Test
        @DisplayName("미국 조건주문은 신선한 환율이 없으면 금액을 몰라 거부함")
        fun usNeedsFreshRate() {
            val nvidia =
                ConditionalFixtures.single(
                    trigger = usd("100"),
                    symbol = TradingFixtures.nvidia,
                )
            val stale = rate(now.minus(Duration.ofMinutes(11)))

            assertThat(rule.check(context(nvidia, fx = null))).isInstanceOf(Violation::class.java)
            assertThat(rule.check(context(nvidia, fx = stale))).isInstanceOf(Violation::class.java)
            assertThat(rule.check(context(nvidia, fx = rate(now)))).isEqualTo(Clear)
        }
    }

    @Test
    @DisplayName("묶음은 위반과 노트를 함께 모음")
    fun chainCollectsAllFindings() {
        val expiredHighValue =
            ConditionalFixtures.oco(
                quantity = Quantity.of(1),
                takeProfit = krw("200000000"),
                expire = today.minusDays(1),
            )

        val verdict = ConditionalOrderGuardrails.evaluate(context(expiredHighValue))

        assertThat(verdict).isInstanceOf(GuardrailVerdict.Rejected::class.java)
        assertThat((verdict as GuardrailVerdict.Rejected).violations.map { it.rule })
            .containsExactly("ConditionalExpiry")
        assertThat(verdict.notes.map { it.rule }).containsExactly(HighValueOrder.NAME)
    }

    @Test
    @DisplayName("다른 통화의 환율로는 판정하지 않음")
    fun rejectsForeignRate() {
        assertThatThrownBy { context(ConditionalFixtures.oco(), fx = rate(now)) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    private fun context(intent: ConditionalOrderIntent, fx: ExchangeRate? = null) =
        ConditionalGuardrailContext(intent, today, fx, now)

    private fun rate(asOf: Instant) =
        ExchangeRate(Currency.USD, Currency.KRW, BigDecimal("1400"), asOf)
}
