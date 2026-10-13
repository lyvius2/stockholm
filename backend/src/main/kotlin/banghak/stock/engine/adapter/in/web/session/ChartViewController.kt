package banghak.stock.engine.adapter.`in`.web.session

import banghak.stock.core.domain.account.ChartView
import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.account.SimpleChartRange
import banghak.stock.core.domain.trading.ChartResolution
import banghak.stock.core.usecase.ChartViewSettingUseCase
import banghak.stock.engine.adapter.`in`.web.common.enumOf
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 조회 응답과 저장 본문이 같은 모양(`api-chart-view`). */
data class ChartViewDto(
    val isDetailed: Boolean = false,
    val simpleRange: String = "",
    val detailedResolution: String = "",
) {
    fun toDomain() =
        ChartView(
            isDetailed = isDetailed,
            simpleRange = enumOf<SimpleChartRange>(simpleRange, "간단 차트 기간"),
            detailedResolution = enumOf<ChartResolution>(detailedResolution, "봉 단위"),
        )

    companion object {
        fun of(view: ChartView) =
            ChartViewDto(view.isDetailed, view.simpleRange.name, view.detailedResolution.name)
    }
}

/**
 * 차트 보기 설정(F3): 자세한 차트 토글과 모드별 마지막 기간 탭.
 * 사용자 설정이라 세션 사용자의 것만 읽고 쓰며 디바이스 간 동기화됨.
 */
@RestController
@RequestMapping("/session")
@Profile(RuntimeProfiles.ENGINE)
class ChartViewController(private val chartViews: ChartViewSettingUseCase) {
    @GetMapping("/chart-view")
    fun chartView(principal: Principal): ChartViewDto =
        ChartViewDto.of(chartViews.chartView(principal.userId))

    @PutMapping("/chart-view")
    fun saveChartView(principal: Principal, @RequestBody request: ChartViewDto): ChartViewDto {
        val view = request.toDomain()
        chartViews.saveChartView(principal.userId, principal.session.deviceId, view)
        return ChartViewDto.of(view)
    }
}
