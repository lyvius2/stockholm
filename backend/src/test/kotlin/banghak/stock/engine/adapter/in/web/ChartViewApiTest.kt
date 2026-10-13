package banghak.stock.engine.adapter.`in`.web

import banghak.stock.core.domain.account.ChartView
import banghak.stock.core.domain.account.SimpleChartRange
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.ChartResolution
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.usecase.ChartViewSettingUseCase
import banghak.stock.engine.adapter.`in`.web.session.ChartViewController
import banghak.stock.support.web.ApiTestSupport
import banghak.stock.support.web.ApiTestSupport.assertConforms
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 차트 보기 설정이 세션 사용자·디바이스 범위에서 읽히고 저장됨. */
class ChartViewApiTest {
    private val saved = mutableMapOf<UserId, Pair<DeviceId, ChartView>>()
    private val chartViews =
        object : ChartViewSettingUseCase {
            override fun chartView(userId: UserId) = saved[userId]?.second ?: ChartView.DEFAULT

            override fun saveChartView(userId: UserId, deviceId: DeviceId, view: ChartView) {
                saved[userId] = deviceId to view
            }
        }
    private val mvc = ApiTestSupport.mockMvc(ChartViewController(chartViews))

    @Test
    @DisplayName("설정이 없으면 기본값(간단 차트·장중·일봉)을 줌")
    fun defaultsWhenUnset() {
        val body =
            mvc.perform(get("/session/chart-view"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.isDetailed").value(false))
                .andExpect(jsonPath("$.simpleRange").value("INTRADAY"))
                .andExpect(jsonPath("$.detailedResolution").value("DAY"))
                .andReturn()
                .response
                .contentAsString
        assertConforms(body, "api-chart-view")
    }

    @Test
    @DisplayName("저장은 세션 사용자·디바이스로 하고 저장한 값을 돌려줌")
    fun savesForSessionUser() {
        val body =
            mvc.perform(
                    put("/session/chart-view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"isDetailed":true,"simpleRange":"YEAR_1","detailedResolution":"MINUTE_5"}"""
                        )
                )
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
        assertConforms(body, "api-chart-view")

        assertThat(saved[TradingFixtures.user])
            .isEqualTo(
                TradingFixtures.device to
                    ChartView(true, SimpleChartRange.YEAR_1, ChartResolution.MINUTE_5)
            )
    }

    @Test
    @DisplayName("모르는 봉 단위는 400 이고 저장하지 않음")
    fun rejectsUnknownResolution() {
        mvc.perform(
                put("/session/chart-view")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"isDetailed":true,"simpleRange":"ALL","detailedResolution":"MINUTE_15"}"""
                    )
            )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("InvalidValueException"))

        assertThat(saved).isEmpty()
    }
}
