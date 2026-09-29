package banghak.stock.core.domain.market

import banghak.stock.core.domain.error.InvalidValueException
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 종목이 상장된 시장.
 * 토스 종목 정보의 구분 그대로이며, 기타(KR_ETC·US_ETC)는 주요 시장 밖으로 묶인 종목임.
 */
enum class ListingBoard(val market: Market) {
    KOSPI(Market.KR),
    KOSDAQ(Market.KR),
    KR_ETC(Market.KR),
    NYSE(Market.US),
    NASDAQ(Market.US),
    AMEX(Market.US),
    US_ETC(Market.US),
}

/**
 * 증권 종류.
 * 증권사가 새 값을 더할 수 있어 모르는 값은 [UNKNOWN] 임.
 */
enum class SecurityType {
    STOCK,
    FOREIGN_STOCK,
    DEPOSITARY_RECEIPT,
    INFRASTRUCTURE_FUND,
    REIT,
    ETF,
    FOREIGN_ETF,
    ETN,
    STOCK_WARRANTS,
    UNKNOWN,
}

/** 상장 상태. */
enum class ListingStatus {
    SCHEDULED,
    ACTIVE,
    DELISTED,
}

/**
 * 국내 종목의 거래 상태.
 * [isNxtSuspended] 는 NXT 미지원 종목이면 null 임.
 */
data class KrTradingDetail(
    val isLiquidationTrading: Boolean,
    val isNxtSupported: Boolean,
    val isKrxSuspended: Boolean,
    val isNxtSuspended: Boolean?,
)

/**
 * 종목 기본 정보.
 * 종목 마스터의 한 행이며 하루 한 번 통째로 갱신함.
 * [krDetail] 은 국내 종목에만 있음.
 */
data class StockProfile(
    val symbol: Symbol,
    val name: String,
    val englishName: String,
    val isin: String,
    val board: ListingBoard,
    val securityType: SecurityType,
    val isCommonShare: Boolean,
    val status: ListingStatus,
    val listedOn: LocalDate?,
    val delistedOn: LocalDate?,
    val sharesOutstanding: BigDecimal,
    val leverageFactor: BigDecimal?,
    val krDetail: KrTradingDetail?,
) {
    init {
        if (name.isBlank()) throw InvalidValueException("$symbol 의 종목명이 비어 있음")
        if (board.market != symbol.market)
            throw InvalidValueException("$symbol 은 ${board} 에 상장될 수 없음")
        if (krDetail != null && symbol.market != Market.KR)
            throw InvalidValueException("국내 거래 상태는 국내 종목에만 있음: $symbol")
    }

    val isPreferred: Boolean
        get() = securityType == SecurityType.STOCK && !isCommonShare

    val isDelisted: Boolean
        get() = status == ListingStatus.DELISTED

    val chosung: String
        get() = Chosung.of(name)
}

/**
 * 매수 유의사항 종류.
 * 증권사가 새 값을 더할 수 있어 모르는 값은 [UNKNOWN] 임.
 */
enum class StockWarningType {
    LIQUIDATION_TRADING,
    OVERHEATED,
    INVESTMENT_WARNING,
    INVESTMENT_RISK,
    VI_STATIC_AND_DYNAMIC,
    VI_STATIC,
    VI_DYNAMIC,
    STOCK_WARRANTS,
    UNKNOWN,
}

/**
 * 지금 걸려 있는 매수 유의사항 하나.
 * 날짜는 KST 기준이며 양 끝을 포함함.
 * 날짜가 null 이면 미정이거나 진행 중임.
 */
data class StockWarning(
    val type: StockWarningType,
    val startsOn: LocalDate?,
    val endsOn: LocalDate?,
)
