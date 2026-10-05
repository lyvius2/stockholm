package banghak.stock.engine.adapter.`in`.web.market

import banghak.stock.core.domain.error.NotFoundException
import banghak.stock.core.domain.market.StockQuery
import banghak.stock.core.domain.market.StockSummary
import banghak.stock.core.usecase.LookupStockUseCase
import banghak.stock.engine.adapter.`in`.web.common.SymbolDto
import banghak.stock.shared.config.RuntimeProfiles
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class StockSummaryResponse(
    val symbol: SymbolDto,
    val name: String,
    val englishName: String,
    val board: String,
    val securityType: String,
    @get:JsonProperty("isPreferred") val isPreferred: Boolean,
) {
    companion object {
        fun of(summary: StockSummary) =
            StockSummaryResponse(
                SymbolDto.of(summary.symbol),
                summary.name,
                summary.englishName,
                summary.board.name,
                summary.securityType.name,
                summary.isPreferred,
            )
    }
}

/**
 * 종목 마스터 조회·검색(F4).
 * 공용 데이터라 세션만 있으면 됨.
 */
@RestController
@RequestMapping("/stocks")
@Profile(RuntimeProfiles.ENGINE)
class StockController(private val stocks: LookupStockUseCase) {
    @GetMapping
    fun search(
        @RequestParam query: String,
        @RequestParam(defaultValue = StockQuery.DEFAULT_LIMIT.toString()) limit: Int,
    ): List<StockSummaryResponse> =
        stocks.search(StockQuery(query, limit)).map(StockSummaryResponse::of)

    @GetMapping("/{market}/{code}")
    fun find(@PathVariable market: String, @PathVariable code: String): StockSummaryResponse {
        val symbol = SymbolDto(market, code).toSymbol()
        return StockSummaryResponse.of(
            stocks.find(symbol) ?: throw NotFoundException("상장 중인 종목이 아님: $symbol")
        )
    }
}
