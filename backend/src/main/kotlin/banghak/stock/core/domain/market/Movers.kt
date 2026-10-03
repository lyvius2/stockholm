package banghak.stock.core.domain.market

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.money.Percent
import java.math.BigDecimal
import java.time.Instant

/** 직전 갱신과 비교한 순위 변동. */
sealed interface RankChange {
    data object New : RankChange

    data object Same : RankChange

    data class Up(val places: Int) : RankChange

    data class Down(val places: Int) : RankChange
}

/**
 * 급등락 한 줄.
 * [changeRate] 는 전일 기준가 대비이며 기준가가 0이면 없음.
 * [flags] 는 종목 경고(투자경고·관리·거래정지 등)이고 받지 못하면 없음.
 */
data class Mover(
    val rank: Int,
    val symbol: Symbol,
    val last: Money,
    val changeRate: Percent?,
    val tradingVolume: BigDecimal,
    val rankChange: RankChange,
    val flags: StockFlags?,
)

/**
 * 한 시장의 급등 5·급락 5.
 * 토스 급등락 랭킹은 실시간이 아니라, 1일 등락률 상위 100을 받아 현재가로 다시 계산해 정렬함.
 * [session] 은 지금 장 세션이며 미국 정규장 밖이면 화면이 프리·애프터·전일 기준임을 칩으로 밝힘.
 * [isDelayed] 가 true 면 현재가를 못 받아 랭킹의 가격을 그대로 썼음.
 */
data class MoverBoard(
    val market: Market,
    val gainers: List<Mover>,
    val losers: List<Mover>,
    val session: MarketSession,
    val asOf: Instant,
    val isDelayed: Boolean,
) {
    init {
        if (gainers.size > TOP_N || losers.size > TOP_N)
            throw InvalidValueException("급등·급락은 각각 ${TOP_N}개까지임")
    }

    companion object {
        const val TOP_N = 5
    }
}

/** 랭킹 종목을 현재가로 다시 계산해 급등·급락을 가림. */
object MoverRanker {
    /**
     * 등락률 = (현재가 − 기준가) ÷ 기준가.
     * 현재가가 없는 종목은 랭킹의 가격을 씀.
     * 기준가가 0인 종목은 등락률을 낼 수 없어 뺌.
     * 급등은 오른 종목만 등락률 내림차순, 급락은 내린 종목만 오름차순으로 각 5개이고, 같으면 종목 코드 순.
     * 보합(0%)은 어느 쪽에도 넣지 않아 한 종목이 양쪽에 나오지 않음.
     * 순위 변동은 직전 판 같은 쪽의 순위와 비교함.
     */
    fun rank(
        candidates: List<RankedStock>,
        quotes: Map<Symbol, Money>,
        previous: MoverBoard?,
        flags: Map<Symbol, StockFlags>,
    ): Pair<List<Mover>, List<Mover>> {
        val scored =
            candidates
                .distinctBy { it.symbol }
                .mapNotNull { scored(it, quotes[it.symbol] ?: it.last) }
        val gainers =
            scored
                .filter { it.changeRate.exceeds(Percent.ZERO) }
                .sortedWith(compareByDescending<Scored> { it.changeRate }.thenBy { it.symbol.code })
                .take(MoverBoard.TOP_N)
        val losers =
            scored
                .filter { Percent.ZERO.exceeds(it.changeRate) }
                .sortedWith(compareBy<Scored> { it.changeRate }.thenBy { it.symbol.code })
                .take(MoverBoard.TOP_N)
        return movers(gainers, previous?.gainers, flags) to movers(losers, previous?.losers, flags)
    }

    private data class Scored(
        val symbol: Symbol,
        val last: Money,
        val changeRate: Percent,
        val tradingVolume: BigDecimal,
    )

    private fun scored(stock: RankedStock, last: Money): Scored? {
        if (stock.base.isZero) return null
        return Scored(
            stock.symbol,
            last,
            last.minus(stock.base).ratioTo(stock.base),
            stock.tradingVolume,
        )
    }

    private fun movers(
        scored: List<Scored>,
        previous: List<Mover>?,
        flags: Map<Symbol, StockFlags>,
    ): List<Mover> = scored.mapIndexed { index, it ->
        val rank = index + 1
        Mover(
            rank = rank,
            symbol = it.symbol,
            last = it.last,
            changeRate = it.changeRate,
            tradingVolume = it.tradingVolume,
            rankChange =
                rankChangeOf(rank, previous?.firstOrNull { p -> p.symbol == it.symbol }?.rank),
            flags = flags[it.symbol],
        )
    }

    private fun rankChangeOf(rank: Int, previousRank: Int?): RankChange =
        when {
            previousRank == null -> RankChange.New
            previousRank == rank -> RankChange.Same
            previousRank > rank -> RankChange.Up(previousRank - rank)
            else -> RankChange.Down(rank - previousRank)
        }
}
