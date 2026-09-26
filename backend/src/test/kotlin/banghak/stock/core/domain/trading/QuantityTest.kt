package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.money.Percent
import java.math.RoundingMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class QuantityTest {
    @Test
    @DisplayName("음수는 거부하고 0은 허용함")
    fun rejectsNegativeAllowsZero() {
        assertThat(Quantity.ZERO.isZero).isTrue()
        assertThatThrownBy { Quantity.of("-1") }.isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("소수 7자리부터는 거부함")
    fun rejectsMoreThanSixDecimals() {
        assertThat(Quantity.of("0.000001").value).isEqualByComparingTo("0.000001")
        assertThatThrownBy { Quantity.of("0.0000001") }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("시장별 자릿수 검증은 국내 정수·미국 6자리")
    fun validatesScalePerMarket() {
        assertThat(Quantity.of("10").isValidFor(Market.KR)).isTrue()
        assertThat(Quantity.of("10.5").isValidFor(Market.KR)).isFalse()
        assertThat(Quantity.of("10.5").isValidFor(Market.US)).isTrue()
        assertThat(Quantity.of("10.000000").isWholeShares).isTrue()
        assertThat(Quantity.of("10.000001").isWholeShares).isFalse()
    }

    @Test
    @DisplayName("자릿수만 다른 값은 같음")
    fun ignoresTrailingZeros() {
        assertThat(Quantity.of("10")).isEqualTo(Quantity.of("10.0"))
        assertThat(Quantity.of(10)).isEqualTo(Quantity.of("10.000000"))
    }

    @Test
    @DisplayName("더하기 빼기와 부족한 빼기")
    fun addsSubtractsAndRejectsUnderflow() {
        assertThat(Quantity.of(10).plus(Quantity.of("0.5"))).isEqualTo(Quantity.of("10.5"))
        assertThat(Quantity.of(10).minus(Quantity.of(4))).isEqualTo(Quantity.of(6))
        assertThatThrownBy { Quantity.of(4).minus(Quantity.of(10)) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("비율 곱은 지정한 반올림으로 정수 주수를 만듦")
    fun timesPercentRoundsToWholeShares() {
        assertThat(Quantity.of(100).times(Percent.ofPercent("90"), RoundingMode.DOWN))
            .isEqualTo(Quantity.of(90))
        assertThat(Quantity.of(7).times(Percent.ofPercent("90"), RoundingMode.DOWN))
            .isEqualTo(Quantity.of(6))
        assertThat(Quantity.of(7).times(Percent.ofPercent("90"), RoundingMode.HALF_UP))
            .isEqualTo(Quantity.of(6))
    }

    @Test
    @DisplayName("크기 비교")
    fun comparesSize() {
        assertThat(Quantity.of(5).isGreaterThan(Quantity.of("4.999999"))).isTrue()
        assertThat(Quantity.of(5).isGreaterThan(Quantity.of(5))).isFalse()
    }
}
