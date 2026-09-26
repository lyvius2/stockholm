package banghak.stock.core.domain.money

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PercentTest {
    @Test
    @DisplayName("비율과 백분율 표기가 같은 값을 만듦")
    fun ratioAndPercentNotationsAreEqual() {
        assertThat(Percent.ofPercent("90")).isEqualTo(Percent.ofRatio("0.9"))
        assertThat(Percent.ofPercent("0.5")).isEqualTo(Percent.ofRatio("0.005"))
        assertThat(Percent.HUNDRED).isEqualTo(Percent.ofRatio("1"))
    }

    @Test
    @DisplayName("자릿수만 다른 값은 같음")
    fun ignoresTrailingZeros() {
        assertThat(Percent.ofRatio("0.90")).isEqualTo(Percent.ofRatio("0.9"))
        assertThat(Percent.ofRatio("0.0")).isEqualTo(Percent.ZERO)
    }

    @Test
    @DisplayName("경계값 비교는 같은 값을 이상으로 치고 초과로 치지 않음")
    fun boundaryIsAtLeastButDoesNotExceed() {
        val ninety = Percent.ofPercent("90")
        assertThat(ninety.isAtLeast(Percent.ofPercent("90"))).isTrue()
        assertThat(ninety.exceeds(Percent.ofPercent("90"))).isFalse()
        assertThat(Percent.ofPercent("90.000001").exceeds(ninety)).isTrue()
    }

    @Test
    @DisplayName("배수 곱")
    fun multipliesByFactor() {
        assertThat(Percent.ofPercent("50").times(BigDecimal("0.5")))
            .isEqualTo(Percent.ofPercent("25"))
    }
}
