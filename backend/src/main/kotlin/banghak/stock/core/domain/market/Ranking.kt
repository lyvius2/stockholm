package banghak.stock.core.domain.market

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.money.Percent
import java.math.BigDecimal
import java.time.Instant

/**
 * 랭킹 기준.
 * 거래대금·거래량은 시장 전체 기준과 토스증권 체결 기준이 따로 있음.
 */
enum class RankingType(val isChangeRate: Boolean) {
    MARKET_TRADING_AMOUNT(isChangeRate = false),
    MARKET_TRADING_VOLUME(isChangeRate = false),
    TOP_GAINERS(isChangeRate = true),
    TOP_LOSERS(isChangeRate = true),
    TOSS_SECURITIES_TRADING_AMOUNT(isChangeRate = false),
    TOSS_SECURITIES_TRADING_VOLUME(isChangeRate = false),
}

/** 랭킹 산정 기간(거래일 기준). */
enum class RankingPeriod {
    REALTIME,
    DAY_1,
    WEEK_1,
    MONTH_1,
    MONTH_3,
    MONTH_6,
    YEAR_1,
}

/**
 * 랭킹 조회 조건.
 * 토스는 상위 100위까지 주며, 급등·급락 랭킹은 실시간 기간을 지원하지 않음.
 */
data class RankingQuery(
    val market: Market,
    val type: RankingType,
    val period: RankingPeriod,
    val excludesInvestmentCaution: Boolean,
    val count: Int = MAX_COUNT,
) {
    init {
        if (count !in 1..MAX_COUNT) throw InvalidValueException("랭킹 조회 수는 1~$MAX_COUNT: $count")
        if (type.isChangeRate && period == RankingPeriod.REALTIME)
            throw InvalidValueException("급등·급락 랭킹은 실시간 기간이 없음. 1일 이상으로 조회할 것")
    }

    companion object {
        const val MAX_COUNT = 100
    }
}

/**
 * 랭킹의 한 종목.
 * [base] 와 [changeRate] 는 급등·급락 랭킹이면 기간 시작 기준, 나머지는 전일 기준임.
 * [changeRate] 는 기준가가 0 이면 없음.
 * 거래량·거래대금은 기간 누적임.
 */
data class RankedStock(
    val rank: Int,
    val symbol: Symbol,
    val last: Money,
    val base: Money,
    val changeRate: Percent?,
    val tradingVolume: BigDecimal,
    val tradingAmount: Money,
)

/**
 * 랭킹 조회 결과(순위 오름차순).
 * 집계되지 않은 조합은 빈 목록이고 [rankedAt] 이 없음.
 * 시세를 받지 못한 종목은 빠져 요청한 수보다 적을 수 있음.
 */
data class Ranking(val rankedAt: Instant?, val stocks: List<RankedStock>)
