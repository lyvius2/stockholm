package banghak.stock.engine.adapter.`in`.web.market

import banghak.stock.core.domain.market.Mover
import banghak.stock.core.domain.market.MoverBoard
import banghak.stock.core.domain.market.RankChange
import banghak.stock.core.domain.market.StockFlags
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.Chart
import banghak.stock.core.domain.trading.ChartBar
import banghak.stock.core.domain.trading.ChartResolution
import banghak.stock.engine.adapter.`in`.web.common.MoneyDto
import banghak.stock.engine.adapter.`in`.web.common.SymbolDto
import banghak.stock.engine.adapter.`in`.web.common.toPlainString
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * 차트 응답.
 * 봉은 시각 오름차순이고 평균은 봉과 같은 길이의 배열(앞쪽 모자란 구간은 null).
 */
data class ChartResponse(
    val symbol: SymbolDto,
    val resolution: String,
    val bars: List<ChartBarDto>,
    val closeAverages: Map<String, List<MoneyDto?>>,
    val volumeAverage: List<String?>,
    val nextBefore: Instant?,
    @get:JsonProperty("isDelayed") val isDelayed: Boolean,
) {
    companion object {
        fun of(symbol: Symbol, resolution: ChartResolution, chart: Chart) =
            ChartResponse(
                symbol = SymbolDto.of(symbol),
                resolution = resolution.name,
                bars = chart.bars.map(ChartBarDto::of),
                closeAverages =
                    chart.closeAverages
                        .mapKeys { (window, _) -> window.toString() }
                        .mapValues { (_, averages) -> averages.map { it?.let(MoneyDto::of) } },
                volumeAverage = chart.volumeAverage.map { it?.toPlainString() },
                nextBefore = chart.nextBefore,
                isDelayed = chart.isDelayed,
            )
    }
}

data class ChartBarDto(
    val openTime: Instant,
    val open: MoneyDto,
    val high: MoneyDto,
    val low: MoneyDto,
    val close: MoneyDto,
    val volume: String,
) {
    companion object {
        fun of(bar: ChartBar) =
            ChartBarDto(
                bar.openTime,
                MoneyDto.of(bar.open),
                MoneyDto.of(bar.high),
                MoneyDto.of(bar.low),
                MoneyDto.of(bar.close),
                bar.volume.toString(),
            )
    }
}

/**
 * 급등락 판.
 * 급등·급락 각 5개까지.
 */
data class MoverBoardResponse(
    val market: String,
    val gainers: List<MoverDto>,
    val losers: List<MoverDto>,
    val session: String,
    val asOf: Instant,
    @get:JsonProperty("isDelayed") val isDelayed: Boolean,
) {
    companion object {
        fun of(board: MoverBoard) =
            MoverBoardResponse(
                board.market.name,
                board.gainers.map(MoverDto::of),
                board.losers.map(MoverDto::of),
                board.session.name,
                board.asOf,
                board.isDelayed,
            )
    }
}

data class MoverDto(
    val rank: Int,
    val symbol: SymbolDto,
    val last: MoneyDto,
    val changeRate: String?,
    val tradingVolume: String,
    val rankChange: RankChangeDto,
    val flags: StockFlagsDto?,
) {
    companion object {
        fun of(mover: Mover) =
            MoverDto(
                mover.rank,
                SymbolDto.of(mover.symbol),
                MoneyDto.of(mover.last),
                mover.changeRate?.toPlainString(),
                mover.tradingVolume.toPlainString(),
                RankChangeDto.of(mover.rankChange),
                mover.flags?.let(StockFlagsDto::of),
            )
    }
}

/**
 * 직전 판 대비 순위 변동.
 * places 는 UP·DOWN 에만 있음.
 */
data class RankChangeDto(val kind: String, val places: Int?) {
    companion object {
        fun of(change: RankChange) =
            when (change) {
                RankChange.New -> RankChangeDto("NEW", null)
                RankChange.Same -> RankChangeDto("SAME", null)
                is RankChange.Up -> RankChangeDto("UP", change.places)
                is RankChange.Down -> RankChangeDto("DOWN", change.places)
            }
    }
}

/**
 * 종목 경고 플래그.
 * 거래정지·관리종목은 출처가 없으면 null(모름).
 */
data class StockFlagsDto(
    @get:JsonProperty("isInvestmentWarning") val isInvestmentWarning: Boolean,
    @get:JsonProperty("isInvestmentRisk") val isInvestmentRisk: Boolean,
    @get:JsonProperty("isOverheated") val isOverheated: Boolean,
    @get:JsonProperty("isLiquidationTrading") val isLiquidationTrading: Boolean,
    @get:JsonProperty("isViStatic") val isViStatic: Boolean,
    @get:JsonProperty("isViDynamic") val isViDynamic: Boolean,
    @get:JsonProperty("hasUnknownWarning") val hasUnknownWarning: Boolean,
    @get:JsonProperty("isTradingHalted") val isTradingHalted: Boolean?,
    @get:JsonProperty("isAdministrative") val isAdministrative: Boolean?,
    val asOf: Instant,
) {
    companion object {
        fun of(flags: StockFlags) =
            StockFlagsDto(
                isInvestmentWarning = flags.isInvestmentWarning,
                isInvestmentRisk = flags.isInvestmentRisk,
                isOverheated = flags.isOverheated,
                isLiquidationTrading = flags.isLiquidationTrading,
                isViStatic = flags.isViStatic,
                isViDynamic = flags.isViDynamic,
                hasUnknownWarning = flags.hasUnknownWarning,
                isTradingHalted = flags.isTradingHalted,
                isAdministrative = flags.isAdministrative,
                asOf = flags.asOf,
            )
    }
}
