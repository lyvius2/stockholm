package banghak.stock.engine.adapter.`in`.web

import banghak.stock.core.domain.market.ListingBoard
import banghak.stock.core.domain.market.SecurityType
import banghak.stock.core.domain.market.StockQuery
import banghak.stock.core.domain.market.StockSummary
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.usecase.LookupStockUseCase
import banghak.stock.engine.adapter.`in`.web.market.StockController
import banghak.stock.support.web.ApiTestSupport
import banghak.stock.support.web.ApiTestSupport.assertConforms
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 종목 조회·검색 REST.
 * 없는 종목은 404, 응답은 스키마에 맞음.
 */
class StockApiTest {
    private val queries = mutableListOf<StockQuery>()
    private val samsung =
        StockSummary(
            TradingFixtures.samsung,
            "삼성전자",
            "Samsung Electronics",
            ListingBoard.KOSPI,
            SecurityType.STOCK,
            false,
        )
    private val stocks =
        object : LookupStockUseCase {
            override fun find(symbol: Symbol) = samsung.takeIf { symbol == it.symbol }

            override fun search(query: StockQuery): List<StockSummary> {
                queries += query
                return listOf(samsung)
            }
        }
    private val mvc = ApiTestSupport.mockMvc(StockController(stocks))

    @Test
    @DisplayName("검색어와 결과 수를 그대로 묻고 요약 배열을 돌려줌")
    fun search() {
        val body =
            mvc.perform(get("/stocks").param("query", "ㅅㅅ").param("limit", "5"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].name").value("삼성전자"))
                .andExpect(jsonPath("$[0].symbol.code").value("005930"))
                .andExpect(jsonPath("$[0].isPreferred").value(false))
                .andReturn()
                .response
                .contentAsString

        assertThat(queries).containsExactly(StockQuery("ㅅㅅ", 5))
        assertConforms(body.removePrefix("[").removeSuffix("]"), "api-stock-summary")
    }

    @Test
    @DisplayName("빈 검색어는 400, 결과 수를 주지 않으면 20")
    fun searchValidation() {
        mvc.perform(get("/stocks").param("query", " ")).andExpect(status().isBadRequest)
        mvc.perform(get("/stocks").param("query", "삼성")).andExpect(status().isOk)
        assertThat(queries.single().limit).isEqualTo(StockQuery.DEFAULT_LIMIT)
    }

    @Test
    @DisplayName("종목 하나는 경로로 찾고, 상장 중이 아니면 404")
    fun findOne() {
        val body =
            mvc.perform(get("/stocks/KR/005930"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.board").value("KOSPI"))
                .andReturn()
                .response
                .contentAsString
        assertConforms(body, "api-stock-summary")

        mvc.perform(get("/stocks/KR/000000"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NotFoundException"))
    }
}
