package banghak.stock.core.domain.money

import banghak.stock.core.domain.error.CurrencyMismatch
import banghak.stock.core.domain.error.InvalidValue
import banghak.stock.core.domain.trading.Quantity
import java.math.BigDecimal

/** 금액. 통화가 다른 값끼리는 연산하지 않음(`CurrencyMismatch`). 생성자는 통화 자릿수에 맞는 금액만 받고, 반올림은 팩토리 `of`가 함. */
data class Money(val amount: BigDecimal, val currency: Currency) : Comparable<Money> {
    init {
        if (amount.scale() != currency.scale) {
            throw InvalidValue("$currency 금액은 소수 ${currency.scale}자리여야 함: $amount")
        }
    }

    val isPositive: Boolean
        get() = amount.signum() > 0

    val isZero: Boolean
        get() = amount.signum() == 0

    val isNegative: Boolean
        get() = amount.signum() < 0

    fun plus(other: Money): Money = Money(amount + sameCurrency(other).amount, currency)

    fun minus(other: Money): Money = Money(amount - sameCurrency(other).amount, currency)

    fun times(factor: BigDecimal): Money = of(amount * factor, currency)

    fun times(quantity: Quantity): Money = of(amount * quantity.value, currency)

    /** this / base. 비율은 소수 6자리 HALF_EVEN. */
    fun ratioTo(base: Money): Percent {
        if (sameCurrency(base).isZero) throw InvalidValue("0 금액에 대한 비율은 정의되지 않음")
        return Percent(amount.divide(base.amount, RoundingRules.RATIO_SCALE, RoundingRules.MONEY))
    }

    /** 환율의 `from` 통화가 이 금액의 통화여야 함. */
    fun convert(rate: ExchangeRate): Money {
        if (rate.from != currency)
            throw CurrencyMismatch("환율 ${rate.from}→${rate.to} 은 $currency 금액에 쓸 수 없음")
        return of(amount * rate.rate, rate.to)
    }

    override fun compareTo(other: Money): Int = amount.compareTo(sameCurrency(other).amount)

    private fun sameCurrency(other: Money): Money {
        if (other.currency != currency)
            throw CurrencyMismatch("$currency 와 ${other.currency} 는 함께 계산할 수 없음")
        return other
    }

    companion object {
        fun of(amount: BigDecimal, currency: Currency): Money =
            Money(amount.setScale(currency.scale, RoundingRules.MONEY), currency)

        fun of(amount: String, currency: Currency): Money = of(BigDecimal(amount), currency)

        fun of(amount: Long, currency: Currency): Money = of(BigDecimal.valueOf(amount), currency)

        fun zero(currency: Currency): Money = of(BigDecimal.ZERO, currency)
    }
}
