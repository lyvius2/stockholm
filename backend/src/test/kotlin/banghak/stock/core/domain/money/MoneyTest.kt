package banghak.stock.core.domain.money

import banghak.stock.core.domain.error.CurrencyMismatchException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.trading.Quantity
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MoneyTest {
    @Nested
    @DisplayName("자릿수와 반올림")
    inner class ScaleAndRounding {
        @Test
        @DisplayName("KRW는 소수 0자리로 HALF_EVEN 반올림함")
        fun krwRoundsHalfEvenToZeroDecimals() {
            assertThat(Money.of("1234.5", Currency.KRW).amount).isEqualByComparingTo("1234")
            assertThat(Money.of("1235.5", Currency.KRW).amount).isEqualByComparingTo("1236")
            assertThat(Money.of("1234.51", Currency.KRW).amount).isEqualByComparingTo("1235")
        }

        @Test
        @DisplayName("USD는 소수 2자리로 HALF_EVEN 반올림함")
        fun usdRoundsHalfEvenToTwoDecimals() {
            assertThat(Money.of("2.345", Currency.USD).amount).isEqualByComparingTo("2.34")
            assertThat(Money.of("2.355", Currency.USD).amount).isEqualByComparingTo("2.36")
            assertThat(Money.of("2.3451", Currency.USD).amount).isEqualByComparingTo("2.35")
        }

        @Test
        @DisplayName("생성자는 통화 자릿수와 다른 금액을 거부함")
        fun constructorRejectsWrongScale() {
            assertThatThrownBy { Money(BigDecimal("1.5"), Currency.KRW) }
                .isInstanceOf(InvalidValueException::class.java)
            assertThatThrownBy { Money(BigDecimal("1.555"), Currency.USD) }
                .isInstanceOf(InvalidValueException::class.java)
        }

        @Test
        @DisplayName("자릿수만 다른 같은 금액은 같은 값임")
        fun equalRegardlessOfInputScale() {
            assertThat(Money.of("100", Currency.USD)).isEqualTo(Money.of("100.00", Currency.USD))
            assertThat(Money.of("100", Currency.USD).hashCode())
                .isEqualTo(Money.of("100.00", Currency.USD).hashCode())
        }
    }

    @Nested
    @DisplayName("연산")
    inner class Arithmetic {
        @Test
        @DisplayName("같은 통화끼리 더하고 뺌")
        fun addsAndSubtractsSameCurrency() {
            val sum = Money.of("100", Currency.KRW).plus(Money.of("50", Currency.KRW))
            assertThat(sum).isEqualTo(Money.of("150", Currency.KRW))
            assertThat(sum.minus(Money.of("200", Currency.KRW)))
                .isEqualTo(Money.of("-50", Currency.KRW))
        }

        @Test
        @DisplayName("통화가 다르면 CurrencyMismatchException")
        fun rejectsDifferentCurrency() {
            val krw = Money.of("100", Currency.KRW)
            val usd = Money.of("100", Currency.USD)
            assertThatThrownBy { krw.plus(usd) }.isInstanceOf(CurrencyMismatchException::class.java)
            assertThatThrownBy { krw.minus(usd) }
                .isInstanceOf(CurrencyMismatchException::class.java)
            assertThatThrownBy { krw.ratioTo(usd) }
                .isInstanceOf(CurrencyMismatchException::class.java)
            assertThatThrownBy { krw.compareTo(usd) }
                .isInstanceOf(CurrencyMismatchException::class.java)
        }

        @Test
        @DisplayName("배수 곱은 통화 자릿수로 HALF_EVEN 반올림함")
        fun timesFactorRoundsHalfEven() {
            assertThat(Money.of("10.00", Currency.USD).times(BigDecimal("0.3333")))
                .isEqualTo(Money.of("3.33", Currency.USD))
            assertThat(Money.of("1000", Currency.KRW).times(BigDecimal("0.0005")))
                .isEqualTo(Money.of("0", Currency.KRW))
            assertThat(Money.of("1000", Currency.KRW).times(BigDecimal("0.0015")))
                .isEqualTo(Money.of("2", Currency.KRW))
        }

        @Test
        @DisplayName("수량 곱은 소수점 수량도 받음")
        fun timesQuantityAcceptsFractions() {
            assertThat(Money.of("150.25", Currency.USD).times(Quantity.of("2.5")))
                .isEqualTo(Money.of("375.62", Currency.USD))
            assertThat(Money.of("70000", Currency.KRW).times(Quantity.of(3)))
                .isEqualTo(Money.of("210000", Currency.KRW))
        }

        @Test
        @DisplayName("비율은 소수 6자리까지 계산함")
        fun ratioHasSixDecimals() {
            assertThat(
                    Money.of("9000000", Currency.KRW).ratioTo(Money.of("10000000", Currency.KRW))
                )
                .isEqualTo(Percent.ofRatio("0.9"))
            assertThat(Money.of("1", Currency.KRW).ratioTo(Money.of("3", Currency.KRW)))
                .isEqualTo(Percent.ofRatio("0.333333"))
        }

        @Test
        @DisplayName("0으로 비율을 구하면 InvalidValueException")
        fun ratioToZeroIsInvalid() {
            assertThatThrownBy { Money.of("1", Currency.KRW).ratioTo(Money.zero(Currency.KRW)) }
                .isInstanceOf(InvalidValueException::class.java)
        }

        @Test
        @DisplayName("부호 판정")
        fun reportsSign() {
            assertThat(Money.of("1", Currency.KRW).isPositive).isTrue()
            assertThat(Money.zero(Currency.KRW).isZero).isTrue()
            assertThat(Money.of("-1", Currency.KRW).isNegative).isTrue()
        }

        @Test
        @DisplayName("같은 통화끼리 크기를 비교함")
        fun comparesSameCurrency() {
            assertThat(Money.of("1", Currency.KRW)).isLessThan(Money.of("2", Currency.KRW))
            assertThat(Money.of("2.00", Currency.USD))
                .isEqualByComparingTo(Money.of("2", Currency.USD))
        }
    }

    @Nested
    @DisplayName("환산")
    inner class Conversion {
        private val asOf = Instant.parse("2026-09-26T00:00:00Z")

        @Test
        @DisplayName("환율의 from 통화가 맞으면 to 통화 금액이 됨")
        fun convertsWithMatchingFromCurrency() {
            val rate = ExchangeRate(Currency.USD, Currency.KRW, BigDecimal("1350.55"), asOf)
            assertThat(Money.of("10.00", Currency.USD).convert(rate))
                .isEqualTo(Money.of("13506", Currency.KRW))
        }

        @Test
        @DisplayName("환율의 from 통화가 다르면 CurrencyMismatchException")
        fun rejectsRateWithOtherFromCurrency() {
            val rate = ExchangeRate(Currency.USD, Currency.KRW, BigDecimal("1350"), asOf)
            assertThatThrownBy { Money.of("1000", Currency.KRW).convert(rate) }
                .isInstanceOf(CurrencyMismatchException::class.java)
        }
    }
}
