package banghak.stock.core.domain.market

import banghak.stock.core.domain.error.InvalidValue

/** 종목 식별자. 코드 형식은 시장별로 검증함(국내 숫자 6자리, 미국 대문자 티커 1~5자). */
data class Symbol(val market: Market, val code: String) {
    init {
        if (!CODE_PATTERN.getValue(market).matches(code))
            throw InvalidValue("$market 종목 코드 형식이 아님: '$code'")
    }

    override fun toString(): String = "$market:$code"

    companion object {
        private val CODE_PATTERN =
            mapOf(Market.KR to Regex("[0-9]{6}"), Market.US to Regex("[A-Z]{1,5}"))

        /** 로그인 직후 보여 줄 기본 종목. 직전 종목도 보유 종목도 없을 때 씀. */
        val DEFAULT_KR: Symbol = Symbol(Market.KR, "005930")
        val DEFAULT_US: Symbol = Symbol(Market.US, "NVDA")
    }
}
