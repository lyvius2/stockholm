package banghak.stock.core.usecase

import banghak.stock.core.domain.account.ChartView
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId

/**
 * 차트 보기 설정(F3)의 조회·저장.
 * 토글과 기간 탭은 사용자 설정이라 동기화 대상임.
 */
interface ChartViewSettingUseCase {
    /** 설정이 없으면 기본값(간단 차트·장중·일봉). */
    fun chartView(userId: UserId): ChartView

    fun saveChartView(userId: UserId, deviceId: DeviceId, view: ChartView)
}
