package banghak.stock.engine.adapter.`in`.web.common

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.money.Percent
import banghak.stock.core.domain.trading.Quantity
import java.math.BigDecimal
import java.time.Instant

/**
 * REST 와 로컬 WebSocket 이 함께 쓰는 값 객체 표기.
 * 모양은 `protocol/schemas/common/_values.schema.json` 이 원본임.
 * 요청의 잘못된 값은 InvalidValueException(400)으로 올림.
 */
data class SymbolDto(val market: String = "", val code: String = "") {
    fun toSymbol(): Symbol = Symbol(enumOf<Market>(market, "시장"), code)

    companion object {
        fun of(symbol: Symbol) = SymbolDto(symbol.market.name, symbol.code)
    }
}

data class MoneyDto(val amount: String = "", val currency: String = "") {
    fun toMoney(): Money = Money.of(decimalOf(amount, "금액"), enumOf<Currency>(currency, "통화"))

    companion object {
        fun of(money: Money) = MoneyDto(money.amount.toPlainString(), money.currency.name)
    }
}

data class ExchangeRateDto(val from: String, val to: String, val rate: String, val asOf: Instant) {
    companion object {
        fun of(rate: ExchangeRate) =
            ExchangeRateDto(rate.from.name, rate.to.name, rate.rate.toPlainString(), rate.asOf)
    }
}

/** 비율은 소수(0.0123 = 1.23%)의 평문 표기. */
fun Percent.toPlainString(): String = ratio.toPlainString()

fun quantityOf(text: String): Quantity = Quantity.of(decimalOf(text, "수량"))

fun decimalOf(text: String, label: String): BigDecimal =
    try {
        BigDecimal(text)
    } catch (e: NumberFormatException) {
        throw InvalidValueException("$label 이 숫자가 아님: '$text'")
    }

inline fun <reified E : Enum<E>> enumOf(text: String, label: String): E =
    enumValues<E>().firstOrNull { it.name == text }
        ?: throw InvalidValueException("모르는 $label 값: '$text'")
