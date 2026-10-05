package banghak.stock.core.port

import banghak.stock.core.domain.market.MacroObservation
import banghak.stock.core.domain.market.MacroSeries

/**
 * 거시 지표·해외 지수 종가 포트(FRED).
 * 공유 키(admin, 선택)임.
 * 키가 없으면 [banghak.stock.core.domain.error.SecretMissingException], 받지 못하면
 * [banghak.stock.core.domain.error.MarketDataUnavailableException].
 * 개인 화면 표시용이고 재배포하지 않음(출처 이용 조건).
 */
interface MacroIndicatorPort {
    /**
     * 최근 관측값 [count] 개를 최신순으로.
     * 값이 없는 날은 빠져 있음.
     */
    fun recentObservations(series: MacroSeries, count: Int): List<MacroObservation>
}
