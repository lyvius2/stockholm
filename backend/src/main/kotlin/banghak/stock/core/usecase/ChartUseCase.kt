package banghak.stock.core.usecase

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.Chart
import banghak.stock.core.domain.trading.ChartResolution
import java.time.Instant

/**
 * 차트 조회 조건.
 * [before] 가 null 이면 가장 최근 봉부터, 있으면 앞선 조회의 [Chart.nextBefore] 를 넘김.
 */
data class ChartQuery(
    val symbol: Symbol,
    val resolution: ChartResolution,
    val before: Instant?,
    val count: Int,
) {
    init {
        if (count !in 1..MAX_BARS) throw InvalidValueException("차트 봉 수는 1~$MAX_BARS: $count")
    }

    companion object {
        const val MAX_BARS = 500
    }
}

/**
 * 차트(F3).
 * 봉 묶기와 이동평균·거래량 평균 계산은 데몬이 함.
 */
interface LoadChartUseCase {
    /**
     * @param query 종목·봉 단위·조회 위치·봉 수
     * @return 시각 오름차순 봉과 평균. 받을 수 있는 과거가 모자라면 요청한 수보다 적음
     * @throws banghak.stock.core.domain.error.MarketDataUnavailableException 시세를 받지 못하면 발생함
     */
    fun chart(query: ChartQuery): Chart
}

/**
 * 보존 기간(1분봉 90일)이 지난 봉 정리.
 * 일봉은 지우지 않음.
 */
interface PurgeCandlesUseCase {
    fun purgeExpired()
}
