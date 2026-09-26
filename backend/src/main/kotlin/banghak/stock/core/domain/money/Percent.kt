package banghak.stock.core.domain.money

import java.math.BigDecimal

/** 비율. 0.9 = 90%. 백분율 표시 변환은 화면이 함. 자릿수는 정규화해 0.9 와 0.90 이 같은 값임. */
@JvmInline
value class Percent private constructor(val ratio: BigDecimal) : Comparable<Percent> {
    fun isAtLeast(other: Percent): Boolean = this >= other

    fun exceeds(other: Percent): Boolean = this > other

    fun times(factor: BigDecimal): Percent = of(ratio * factor)

    override fun compareTo(other: Percent): Int = ratio.compareTo(other.ratio)

    companion object {
        private val ONE_HUNDRED = BigDecimal(100)

        val ZERO: Percent = of(BigDecimal.ZERO)
        val HUNDRED: Percent = of(BigDecimal.ONE)

        operator fun invoke(ratio: BigDecimal): Percent = of(ratio)

        fun ofRatio(ratio: String): Percent = of(BigDecimal(ratio))

        fun ofPercent(percent: String): Percent = of(BigDecimal(percent).divide(ONE_HUNDRED))

        private fun of(ratio: BigDecimal): Percent {
            val normalized =
                if (ratio.signum() == 0) BigDecimal.ZERO else ratio.stripTrailingZeros()
            return Percent(normalized)
        }
    }
}
