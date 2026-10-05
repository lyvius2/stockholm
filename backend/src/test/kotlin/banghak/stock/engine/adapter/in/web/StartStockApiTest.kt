package banghak.stock.engine.adapter.`in`.web

import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.StartStock
import banghak.stock.core.domain.trading.StartStockReason
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.usecase.LookupStartStockUseCase
import banghak.stock.core.usecase.RecordLastViewedStockUseCase
import banghak.stock.engine.adapter.`in`.web.session.StartStockController
import banghak.stock.support.web.ApiTestSupport
import banghak.stock.support.web.ApiTestSupport.assertConforms
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 시작 종목 조회와 직전 종목 기록이 세션 사용자·디바이스와 데몬 시계로 이뤄짐. */
class StartStockApiTest {
    private data class Recorded(
        val userId: UserId,
        val deviceId: DeviceId,
        val symbol: Symbol,
        val viewedAt: Instant,
    )

    private val recorded = mutableListOf<Recorded>()
    private val now = Instant.parse("2026-10-05T02:00:00Z")
    private val startStocks =
        object : LookupStartStockUseCase {
            override fun startStock(userId: UserId) =
                StartStock(TradingFixtures.nvidia, StartStockReason.LARGEST_POSITION)
        }
    private val lastViewed =
        object : RecordLastViewedStockUseCase {
            override fun record(
                userId: UserId,
                deviceId: DeviceId,
                symbol: Symbol,
                viewedAt: Instant,
            ) {
                recorded += Recorded(userId, deviceId, symbol, viewedAt)
            }
        }
    private val mvc =
        ApiTestSupport.mockMvc(
            StartStockController(startStocks, lastViewed, Clock.fixed(now, ZoneOffset.UTC))
        )

    @Test
    @DisplayName("시작 종목은 종목과 고른 이유를 돌려줌")
    fun startStock() {
        val body =
            mvc.perform(get("/session/start-stock"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.symbol.code").value("NVDA"))
                .andExpect(jsonPath("$.reason").value("LARGEST_POSITION"))
                .andReturn()
                .response
                .contentAsString
        assertConforms(body, "api-start-stock")
    }

    @Test
    @DisplayName("직전 종목은 세션의 사용자·디바이스와 데몬 시계로 기록함")
    fun recordsLastViewedWithSessionAndClock() {
        mvc.perform(
                put("/session/last-viewed-stock")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"symbol":{"market":"KR","code":"005930"}}""")
            )
            .andExpect(status().isOk)

        assertThat(recorded)
            .containsExactly(
                Recorded(TradingFixtures.user, TradingFixtures.device, TradingFixtures.samsung, now)
            )
    }

    @Test
    @DisplayName("모르는 시장이면 400 이고 기록하지 않음")
    fun rejectsUnknownMarket() {
        mvc.perform(
                put("/session/last-viewed-stock")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"symbol":{"market":"JP","code":"7203"}}""")
            )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("InvalidValueException"))

        assertThat(recorded).isEmpty()
    }
}
