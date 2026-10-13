package banghak.stock.core.domain.account

import banghak.stock.core.domain.trading.ChartResolution

/**
 * 간단 차트의 기간 탭.
 * 기간을 봉 단위·봉 수로 바꾸는 것은 화면이 함.
 */
enum class SimpleChartRange {
    INTRADAY,
    DAY_1,
    WEEK_1,
    MONTH_3,
    YEAR_1,
    YEAR_5,
    ALL,
}

/**
 * 차트 보기 설정(F3).
 * "자세한 차트" 토글과 모드별 마지막 기간 탭을 사용자 설정으로 두어 종목·재시작·디바이스가 바뀌어도 유지함.
 */
data class ChartView(
    val isDetailed: Boolean,
    val simpleRange: SimpleChartRange,
    val detailedResolution: ChartResolution,
) {
    companion object {
        val DEFAULT = ChartView(false, SimpleChartRange.INTRADAY, ChartResolution.DAY)
    }
}
