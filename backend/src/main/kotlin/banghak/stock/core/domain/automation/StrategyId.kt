package banghak.stock.core.domain.automation

import banghak.stock.core.domain.error.InvalidValueException

/** 자동 매수 전략 식별자. 결정적 `ClientOrderId`의 재료임. */
@JvmInline
value class StrategyId(val value: String) {
    init {
        if (!PATTERN.matches(value)) throw InvalidValueException("StrategyId 형식이 아님: '$value'")
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[a-z0-9][a-z0-9-]{0,31}")
    }
}
