package banghak.stock.engine.adapter.`in`.web.portfolio

import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.portfolio.HoldingValuation
import banghak.stock.core.domain.portfolio.MarketValuation
import banghak.stock.core.domain.portfolio.PortfolioValuation
import banghak.stock.core.usecase.LookupPortfolioValuationUseCase
import banghak.stock.engine.adapter.`in`.web.common.ExchangeRateDto
import banghak.stock.engine.adapter.`in`.web.common.MoneyDto
import banghak.stock.engine.adapter.`in`.web.common.SymbolDto
import banghak.stock.engine.adapter.`in`.web.common.toPlainString
import banghak.stock.shared.config.RuntimeProfiles
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 보유주식 평가금액 패널(F18).
 * 본인 계좌만.
 */
@RestController
@RequestMapping("/portfolio")
@Profile(RuntimeProfiles.ENGINE)
class PortfolioController(private val valuations: LookupPortfolioValuationUseCase) {
    @GetMapping("/valuation")
    fun valuation(principal: Principal): PortfolioValuationResponse =
        PortfolioValuationResponse.of(valuations.valuation(principal.userId))
}

/**
 * 평가 응답.
 * 전체 원화 합계는 미국 보유가 있는데 환율을 못 받았으면 null.
 */
data class PortfolioValuationResponse(
    val byMarket: List<MarketValuationDto>,
    val totalMarketValueKrw: MoneyDto?,
    val totalProfitLossKrw: MoneyDto?,
    val totalReturnRate: String?,
    val fxUsdKrw: ExchangeRateDto?,
    val cashBuyingPower: Map<String, MoneyDto>,
    @get:JsonProperty("isDelayed") val isDelayed: Boolean,
    val asOf: Instant,
) {
    companion object {
        fun of(valuation: PortfolioValuation) =
            PortfolioValuationResponse(
                byMarket = valuation.byMarket.values.map(MarketValuationDto::of),
                totalMarketValueKrw = valuation.totalMarketValueKrw?.let(MoneyDto::of),
                totalProfitLossKrw = valuation.totalProfitLossKrw?.let(MoneyDto::of),
                totalReturnRate = valuation.totalReturnRate?.toPlainString(),
                fxUsdKrw = valuation.fxUsdKrw?.let(ExchangeRateDto::of),
                cashBuyingPower =
                    valuation.cashBuyingPower
                        .mapKeys { (currency, _) -> currency.name }
                        .mapValues { (_, money) -> MoneyDto.of(money) },
                isDelayed = valuation.isDelayed,
                asOf = valuation.asOf,
            )
    }
}

data class MarketValuationDto(
    val market: String,
    val marketValue: MoneyDto,
    val purchaseAmount: MoneyDto,
    val profitLoss: MoneyDto,
    val returnRate: String?,
    val todayChange: MoneyDto?,
    val risers: Int,
    val fallers: Int,
    val holdings: List<HoldingValuationDto>,
) {
    companion object {
        fun of(valuation: MarketValuation) =
            MarketValuationDto(
                market = valuation.market.name,
                marketValue = MoneyDto.of(valuation.marketValue),
                purchaseAmount = MoneyDto.of(valuation.purchaseAmount),
                profitLoss = MoneyDto.of(valuation.profitLoss),
                returnRate = valuation.returnRate?.toPlainString(),
                todayChange = valuation.todayChange?.let(MoneyDto::of),
                risers = valuation.risers,
                fallers = valuation.fallers,
                holdings = valuation.holdings.map(HoldingValuationDto::of),
            )
    }
}

data class HoldingValuationDto(
    val symbol: SymbolDto,
    val quantity: String,
    val lastPrice: MoneyDto,
    val purchaseAmount: MoneyDto,
    val marketValue: MoneyDto,
    val profitLoss: MoneyDto,
    val previousClose: MoneyDto?,
    val todayChange: MoneyDto?,
) {
    companion object {
        fun of(holding: HoldingValuation) =
            HoldingValuationDto(
                symbol = SymbolDto.of(holding.symbol),
                quantity = holding.quantity.toString(),
                lastPrice = MoneyDto.of(holding.lastPrice),
                purchaseAmount = MoneyDto.of(holding.purchaseAmount),
                marketValue = MoneyDto.of(holding.marketValue),
                profitLoss = MoneyDto.of(holding.profitLoss),
                previousClose = holding.previousClose?.let(MoneyDto::of),
                todayChange = holding.todayChange?.let(MoneyDto::of),
            )
    }
}
