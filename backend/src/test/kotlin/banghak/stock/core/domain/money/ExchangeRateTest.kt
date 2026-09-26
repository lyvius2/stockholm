package banghak.stock.core.domain.money

import banghak.stock.core.domain.error.InvalidValueException
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ExchangeRateTest {
    private val asOf = Instant.parse("2026-09-26T00:00:00Z")

    @Test
    @DisplayName("환율은 양수여야 함")
    fun rejectsNonPositiveRate() {
        assertThatThrownBy { ExchangeRate(Currency.USD, Currency.KRW, BigDecimal.ZERO, asOf) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { ExchangeRate(Currency.USD, Currency.KRW, BigDecimal("-1"), asOf) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("같은 통화 사이의 환율은 만들 수 없음")
    fun rejectsSameCurrencyPair() {
        assertThatThrownBy { ExchangeRate(Currency.KRW, Currency.KRW, BigDecimal.ONE, asOf) }
            .isInstanceOf(InvalidValueException::class.java)
    }
}
