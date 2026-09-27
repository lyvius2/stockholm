package banghak.stock.core.domain.market

import banghak.stock.core.domain.error.InvalidValueException

/**
 * 종목 식별자.
 * 코드 형식은 시장별로 검증함.
 * 국내는 6자리인데 숫자만이 아니라 `0010S0`·`0101N0` 처럼 영문이 섞임(토스 규격·랭킹 실측 2026-09-27).
 * 미국은 대문자 티커에 `.`·`-` 가 올 수 있음(`BRK.B`).
 */
data class Symbol(val market: Market, val code: String) {
    init {
        if (!CODE_PATTERN.getValue(market).matches(code))
            throw InvalidValueException("$market 종목 코드 형식이 아님: '$code'")
    }

    override fun toString(): String = "$market:$code"

    companion object {
        private val CODE_PATTERN =
            mapOf(Market.KR to Regex("[0-9A-Z]{6}"), Market.US to Regex("[A-Z][A-Z0-9.-]{0,9}"))

        /**
         * 로그인 직후 보여 줄 기본 종목.
         * 직전 종목도 보유 종목도 없을 때 씀.
         */
        val DEFAULT_KR: Symbol = Symbol(Market.KR, "005930")
        val DEFAULT_US: Symbol = Symbol(Market.US, "NVDA")
    }
}
