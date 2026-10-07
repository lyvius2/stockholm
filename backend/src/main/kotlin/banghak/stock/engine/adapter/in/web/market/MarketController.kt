package banghak.stock.engine.adapter.`in`.web.market

import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.trading.ChartResolution
import banghak.stock.core.usecase.ChartQuery
import banghak.stock.core.usecase.LoadChartUseCase
import banghak.stock.core.usecase.LookupIndexTickerUseCase
import banghak.stock.core.usecase.LookupMoversUseCase
import banghak.stock.core.usecase.LookupQuoteUseCase
import banghak.stock.engine.adapter.`in`.web.common.IndexTickerDto
import banghak.stock.engine.adapter.`in`.web.common.SymbolDto
import banghak.stock.engine.adapter.`in`.web.common.enumOf
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Instant
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 공용 시세 조회(차트·급등락·지수 티커).
 * 사용자 데이터가 아니라 세션만 있으면 누구나 같은 값을 봄.
 */
@RestController
@RequestMapping("/market")
@Profile(RuntimeProfiles.ENGINE)
class MarketController(
    private val charts: LoadChartUseCase,
    private val movers: LookupMoversUseCase,
    private val indexTicker: LookupIndexTickerUseCase,
    private val quotes: LookupQuoteUseCase,
) {
    /** 스트림이 끊겼을 때 화면이 1초 간격으로 묻는 현재가. */
    @GetMapping("/quote")
    fun quote(@RequestParam market: String, @RequestParam code: String): QuoteResponse =
        QuoteResponse.of(quotes.quote(SymbolDto(market, code).toSymbol()))

    /** 스트림이 끊겼을 때 화면이 2초 간격으로 묻는 호가. */
    @GetMapping("/order-book")
    fun orderBook(@RequestParam market: String, @RequestParam code: String): OrderBookResponse =
        OrderBookResponse.of(quotes.orderBook(SymbolDto(market, code).toSymbol()))

    @GetMapping("/chart")
    fun chart(
        @RequestParam market: String,
        @RequestParam code: String,
        @RequestParam resolution: String,
        @RequestParam(required = false) before: Instant?,
        @RequestParam(defaultValue = DEFAULT_BAR_COUNT) count: Int,
    ): ChartResponse {
        val symbol = SymbolDto(market, code).toSymbol()
        val chartResolution = enumOf<ChartResolution>(resolution, "봉 단위")
        val chart = charts.chart(ChartQuery(symbol, chartResolution, before, count))
        return ChartResponse.of(symbol, chartResolution, chart)
    }

    @GetMapping("/movers")
    fun movers(@RequestParam market: String): MoverBoardResponse =
        MoverBoardResponse.of(movers.board(enumOf<Market>(market, "시장")))

    @GetMapping("/index-ticker")
    fun indexTicker(): IndexTickerDto = IndexTickerDto.of(indexTicker.current())

    companion object {
        // 화면 폭 하나를 채우고도 이동평균 120 을 구할 수 있는 양
        private const val DEFAULT_BAR_COUNT = "300"
    }
}
