package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValue
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.money.Percent
import java.math.BigDecimal
import java.math.RoundingMode

/** 수량. 0 이상, 소수 6자리까지. 시장별 자릿수(국내 정수·미국 6자리)는 [isValidFor]로 검증함. 자릿수는 정규화해 10 과 10.0 이 같은 값임. */
@JvmInline
value class Quantity private constructor(val value: BigDecimal) : Comparable<Quantity> {
    val isZero: Boolean
        get() = value.signum() == 0

    val isWholeShares: Boolean
        get() = value.scale() <= 0

    fun isValidFor(market: Market): Boolean = value.scale() <= market.quantityScale

    fun plus(other: Quantity): Quantity = of(value + other.value)

    fun minus(other: Quantity): Quantity {
        if (other.value > value) throw InvalidValue("수량 $value 에서 ${other.value} 를 뺄 수 없음")
        return of(value - other.value)
    }

    /** 비율 곱. 결과는 정수 주수이며 반올림은 호출자가 정함(한도 계산은 DOWN). */
    fun times(percent: Percent, rounding: RoundingMode): Quantity =
        of((value * percent.ratio).setScale(0, rounding))

    fun isGreaterThan(other: Quantity): Boolean = this > other

    override fun compareTo(other: Quantity): Int = value.compareTo(other.value)

    override fun toString(): String = value.toPlainString()

    companion object {
        private const val MAX_SCALE = 6

        val ZERO: Quantity = of(BigDecimal.ZERO)

        fun of(value: BigDecimal): Quantity {
            if (value.signum() < 0) throw InvalidValue("수량은 음수일 수 없음: $value")
            val normalized =
                if (value.signum() == 0) BigDecimal.ZERO else value.stripTrailingZeros()
            if (normalized.scale() > MAX_SCALE)
                throw InvalidValue("수량은 소수 ${MAX_SCALE}자리까지만 허용함: $value")
            return Quantity(if (normalized.scale() < 0) normalized.setScale(0) else normalized)
        }

        fun of(value: String): Quantity = of(BigDecimal(value))

        fun of(shares: Long): Quantity = of(BigDecimal.valueOf(shares))
    }
}
