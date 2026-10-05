package banghak.stock.core.domain.market

import banghak.stock.core.domain.error.InvalidValueException

/**
 * 종목 마스터의 요약 한 줄.
 * 상단 바 종목명·검색 팝오버·관심종목이 씀.
 */
data class StockSummary(
    val symbol: Symbol,
    val name: String,
    val englishName: String,
    val board: ListingBoard,
    val securityType: SecurityType,
    val isPreferred: Boolean,
) {
    companion object {
        fun of(profile: StockProfile) =
            StockSummary(
                profile.symbol,
                profile.name,
                profile.englishName,
                profile.board,
                profile.securityType,
                profile.isPreferred,
            )
    }
}

/**
 * 종목 검색 조건.
 * [text] 는 종목명·코드·영문명 앞부분이거나 초성(`ㅅㅅㅈㅈ`)임.
 */
data class StockQuery(val text: String, val limit: Int = DEFAULT_LIMIT) {
    init {
        if (text.isBlank()) throw InvalidValueException("검색어가 비어 있음")
        if (limit !in 1..MAX_LIMIT) throw InvalidValueException("검색 결과 수는 1~$MAX_LIMIT: $limit")
    }

    val normalized: String
        get() = text.trim()

    /** 한글 초성만으로 된 검색어인지. */
    val isChosung: Boolean
        get() = normalized.all { it in CHOSUNG_RANGE }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
        private val CHOSUNG_RANGE = 'ㄱ'..'ㅎ'
    }
}
