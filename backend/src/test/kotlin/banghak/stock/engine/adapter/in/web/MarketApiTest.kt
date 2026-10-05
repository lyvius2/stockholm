package banghak.stock.engine.adapter.`in`.web

import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.market.IndexCode
import banghak.stock.core.domain.market.IndexEntry
import banghak.stock.core.domain.market.IndexQuote
import banghak.stock.core.domain.market.IndexSetChoice
import banghak.stock.core.domain.market.IndexSetState
import banghak.stock.core.domain.market.IndexSourceState
import banghak.stock.core.domain.market.IndexTicker
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.MarketSession
import banghak.stock.core.domain.market.Mover
import banghak.stock.core.domain.market.MoverBoard
import banghak.stock.core.domain.market.RankChange
import banghak.stock.core.domain.market.StockFlags
import banghak.stock.core.domain.money.Percent
import banghak.stock.core.domain.trading.Chart
import banghak.stock.core.domain.trading.ChartBar
import banghak.stock.core.domain.trading.ChartResolution
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.usecase.ChartQuery
import banghak.stock.core.usecase.LoadChartUseCase
import banghak.stock.core.usecase.LookupIndexTickerUseCase
import banghak.stock.core.usecase.LookupMoversUseCase
import banghak.stock.engine.adapter.`in`.web.market.MarketController
import banghak.stock.support.web.ApiTestSupport
import banghak.stock.support.web.ApiTestSupport.assertConforms
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 공용 시세 REST 가 질의를 usecase 에 그대로 넘기고 응답이 protocol 스키마에 맞음. */
class MarketApiTest {
    private val queries = mutableListOf<ChartQuery>()
    private var chart: Chart = Chart(emptyList(), emptyMap(), emptyList(), null)
    private var board: () -> MoverBoard = { throw MarketDataUnavailableException("랭킹 없음") }
    private val charts =
        object : LoadChartUseCase {
            override fun chart(query: ChartQuery): Chart {
                queries += query
                return chart
            }
        }
    private val movers =
        object : LookupMoversUseCase {
            override fun board(market: Market): MoverBoard = board()
        }
    private val indexTicker =
        object : LookupIndexTickerUseCase {
            override fun current(): IndexTicker = ticker
        }
    private val mvc = ApiTestSupport.mockMvc(MarketController(charts, movers, indexTicker))

    @Test
    @DisplayName("차트는 종목·봉 단위·조회 위치·봉 수를 그대로 묻고 봉·평균·다음 위치를 돌려줌")
    fun chartQueryAndResponse() {
        val first = Instant.parse("2026-10-05T00:00:00Z")
        val second = Instant.parse("2026-10-05T00:05:00Z")
        chart =
            Chart(
                bars = listOf(bar(first, "70000"), bar(second, "70500")),
                closeAverages = mapOf(5 to listOf(null, krw("70250"))),
                volumeAverage = listOf(null, BigDecimal("1.50")),
                nextBefore = first,
                isDelayed = true,
            )

        val body =
            mvc.perform(
                    get("/market/chart")
                        .param("market", "KR")
                        .param("code", "005930")
                        .param("resolution", "MINUTE_5")
                        .param("before", "2026-10-05T01:00:00Z")
                        .param("count", "2")
                )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.bars[1].close.amount").value("70500"))
                .andExpect(jsonPath("$.closeAverages.5[0]").value(null))
                .andExpect(jsonPath("$.closeAverages.5[1].amount").value("70250"))
                .andExpect(jsonPath("$.volumeAverage[1]").value("1.50"))
                .andExpect(jsonPath("$.nextBefore").value("2026-10-05T00:00:00Z"))
                .andExpect(jsonPath("$.isDelayed").value(true))
                .andReturn()
                .response
                .contentAsString

        assertThat(queries)
            .containsExactly(
                ChartQuery(
                    TradingFixtures.samsung,
                    ChartResolution.MINUTE_5,
                    Instant.parse("2026-10-05T01:00:00Z"),
                    2,
                )
            )
        assertConforms(body, "api-chart")
    }

    @Test
    @DisplayName("봉 수를 주지 않으면 300개를 묻고, 모르는 봉 단위는 400")
    fun chartDefaultsAndBadResolution() {
        mvc.perform(
                get("/market/chart")
                    .param("market", "KR")
                    .param("code", "005930")
                    .param("resolution", "DAY")
            )
            .andExpect(status().isOk)
        assertThat(queries.single().count).isEqualTo(300)
        assertThat(queries.single().before).isNull()

        val error =
            mvc.perform(
                    get("/market/chart")
                        .param("market", "KR")
                        .param("code", "005930")
                        .param("resolution", "MINUTE_7")
                )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("InvalidValueException"))
                .andReturn()
                .response
                .contentAsString
        assertConforms(error, "api-error")
    }

    @Test
    @DisplayName("급등락 판은 순위 변동·플래그까지 실어 주고, 랭킹을 못 받으면 503")
    fun moversBoardAndUnavailable() {
        val asOf = Instant.parse("2026-10-05T01:00:00Z")
        board = {
            MoverBoard(
                market = Market.KR,
                gainers =
                    listOf(
                        Mover(
                            rank = 1,
                            symbol = TradingFixtures.samsung,
                            last = krw("77000"),
                            changeRate = Percent.ofRatio("0.1"),
                            tradingVolume = BigDecimal("1200000"),
                            rankChange = RankChange.Up(2),
                            flags = flags(asOf),
                        )
                    ),
                losers = emptyList(),
                session = MarketSession.REGULAR,
                asOf = asOf,
                isDelayed = false,
            )
        }

        val body =
            mvc.perform(get("/market/movers").param("market", "KR"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.gainers[0].rankChange.kind").value("UP"))
                .andExpect(jsonPath("$.gainers[0].rankChange.places").value(2))
                .andExpect(jsonPath("$.gainers[0].changeRate").value("0.1"))
                .andExpect(jsonPath("$.gainers[0].flags.isTradingHalted").value(null))
                .andExpect(jsonPath("$.session").value("REGULAR"))
                .andReturn()
                .response
                .contentAsString
        assertConforms(body, "api-mover-board")

        board = { throw MarketDataUnavailableException("랭킹 없음") }
        mvc.perform(get("/market/movers").param("market", "US"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("MarketDataUnavailableException"))
    }

    @Test
    @DisplayName("지수 티커는 스트림과 같은 모양으로 돌려줌")
    fun indexTickerMatchesStreamShape() {
        val body =
            mvc.perform(get("/market/index-ticker"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.market").value("KR"))
                .andExpect(jsonPath("$.entries[0].state").value("FRESH"))
                .andExpect(jsonPath("$.entries[1].quote").value(null))
                .andExpect(jsonPath("$.isDelayed").value(true))
                .andReturn()
                .response
                .contentAsString
        assertConforms(body, "api-index-ticker")
    }

    private fun bar(openTime: Instant, close: String) =
        ChartBar(
            symbol = TradingFixtures.samsung,
            resolution = ChartResolution.MINUTE_5,
            openTime = openTime,
            open = krw("70000"),
            high = krw("71000"),
            low = krw("69500"),
            close = krw(close),
            volume = Quantity.of(1000),
        )

    private fun flags(asOf: Instant) =
        StockFlags(
            symbol = TradingFixtures.samsung,
            isInvestmentWarning = false,
            isInvestmentRisk = false,
            isOverheated = true,
            isLiquidationTrading = false,
            isViStatic = false,
            isViDynamic = false,
            hasUnknownWarning = false,
            isTradingHalted = null,
            isAdministrative = false,
            asOf = asOf,
        )

    companion object {
        private val asOf = Instant.parse("2026-10-05T01:00:00Z")
        private val ticker =
            IndexTicker(
                IndexSetChoice(Market.KR, IndexSetState.OPEN),
                listOf(
                    IndexEntry(
                        IndexCode.KOSPI,
                        IndexQuote.of(
                            IndexCode.KOSPI,
                            BigDecimal("2500.12"),
                            BigDecimal("2480.00"),
                            asOf,
                            false,
                            null,
                            "TOSS",
                        ),
                        IndexSourceState.FRESH,
                    ),
                    IndexEntry(IndexCode.KOSDAQ, null, IndexSourceState.DELAYED),
                    IndexEntry(IndexCode.NIKKEI225, null, IndexSourceState.UNCONFIGURED),
                ),
                asOf,
            )
    }
}
