package banghak.stock.core.domain.trading

import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money

/**
 * 로그인 직후 네 영역에 띄울 종목과 그 사유.
 * 기본 종목이면 화면은 토스트를 띄우지 않음.
 */
data class StartStock(val symbol: Symbol, val reason: StartStockReason)

enum class StartStockReason {
    LAST_VIEWED,
    LARGEST_POSITION,
    DEFAULT,
}

/**
 * 보유 종목 하나의 평가금액과 매입원가(둘 다 원화 환산).
 * 순위 비교에만 씀.
 */
data class PositionValuation(val symbol: Symbol, val valuationKrw: Money, val costBasisKrw: Money)

/**
 * 시작 종목 후보.
 * 조회는 engine 이 2초 상한으로 하고, 못 받은 후보는 null 로 넘김.
 * [lastViewedIsListed] 는 종목 마스터에 있고 상장폐지가 아닌지임.
 */
data class StartStockCandidates(
    val lastViewed: Symbol?,
    val lastViewedIsListed: Boolean,
    val valuations: List<PositionValuation>?,
    val defaultMarket: Market,
)

/**
 * 시작 종목 규칙.
 * ⑴ 직전 종목 → ⑵ 평가금액 최대 보유 종목 → ⑶ 기본 종목 상수.
 * 항상 값을 돌려줌.
 */
object StartStockResolver {
    fun resolve(candidates: StartStockCandidates): StartStock {
        candidates.lastViewed
            ?.takeIf { candidates.lastViewedIsListed }
            ?.let {
                return StartStock(it, StartStockReason.LAST_VIEWED)
            }
        largestPosition(candidates.valuations.orEmpty())?.let {
            return StartStock(it, StartStockReason.LARGEST_POSITION)
        }
        return StartStock(defaultSymbol(candidates.defaultMarket), StartStockReason.DEFAULT)
    }

    /**
     * ⑵ 평가금액 같으면 매입원가 큰 쪽, 그래도 같으면 종목 코드 순.
     * 보유가 없으면 null.
     */
    fun largestPosition(valuations: List<PositionValuation>): Symbol? =
        valuations
            .sortedWith(
                compareByDescending<PositionValuation> { it.valuationKrw }
                    .thenByDescending { it.costBasisKrw }
                    .thenBy { it.symbol.code }
            )
            .firstOrNull()
            ?.symbol

    /** ⑶ 기본 시장의 기본 종목 상수. */
    fun defaultSymbol(market: Market): Symbol =
        when (market) {
            Market.KR -> Symbol.DEFAULT_KR
            Market.US -> Symbol.DEFAULT_US
        }
}
