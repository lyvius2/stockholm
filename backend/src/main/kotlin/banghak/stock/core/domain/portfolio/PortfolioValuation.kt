package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.money.Percent
import banghak.stock.core.domain.trading.Quantity
import java.time.Instant

/**
 * 보유 한 종목의 평가.
 * 금액은 그 종목의 거래 통화임.
 * [todayChange] 는 전일 종가 대비 오늘 변동이며 전일 종가를 모르면(신규 상장 등) 없음.
 */
data class HoldingValuation(
    val symbol: Symbol,
    val quantity: Quantity,
    val lastPrice: Money,
    val purchaseAmount: Money,
    val marketValue: Money,
    val profitLoss: Money,
    val previousClose: Money?,
    val todayChange: Money?,
)

/**
 * 한 시장의 평가 합계(그 시장 통화).
 * [returnRate] 는 매수금액이 0이면 없음.
 */
data class MarketValuation(
    val market: Market,
    val marketValue: Money,
    val purchaseAmount: Money,
    val profitLoss: Money,
    val returnRate: Percent?,
    val todayChange: Money?,
    val risers: Int,
    val fallers: Int,
    val holdings: List<HoldingValuation>,
)

/**
 * 보유주식 평가금액 패널(F18)이 보는 값.
 * 전체(원화)는 국내 + 미국 × 환율이며, 미국 보유가 있는데 환율이 없으면 환산하지 않음(null).
 * [isDelayed] 가 true 면 현재가·환율·전일 종가 중 받지 못한 것이 있어 증권사 값이나 마지막 값을 썼음.
 */
data class PortfolioValuation(
    val userId: UserId,
    val byMarket: Map<Market, MarketValuation>,
    val totalMarketValueKrw: Money?,
    val totalProfitLossKrw: Money?,
    val totalReturnRate: Percent?,
    val fxUsdKrw: ExchangeRate?,
    val cashBuyingPower: Map<Currency, Money>,
    val isDelayed: Boolean,
    val asOf: Instant,
)

/**
 * 증권사 보유와 현재가·전일 종가·환율로 평가를 계산함.
 * 현재가가 없는 종목은 증권사가 준 마지막 가격을 씀.
 */
object PortfolioValuator {
    fun valuate(
        userId: UserId,
        holdings: List<BrokerHolding>,
        quotes: Map<Symbol, Money>,
        previousCloses: Map<Symbol, Money>,
        fxUsdKrw: ExchangeRate?,
        cashBuyingPower: Map<Currency, Money>,
        isDelayed: Boolean,
        asOf: Instant,
    ): PortfolioValuation {
        requireUsdKrw(fxUsdKrw)
        val byMarket =
            holdings
                .groupBy { it.symbol.market }
                .mapValues { (market, items) ->
                    marketValuation(market, items.map { valuate(it, quotes, previousCloses) })
                }
        val krw = byMarket[Market.KR]
        val us = byMarket[Market.US]
        val usInKrw = us?.let { fxUsdKrw?.let { fx -> it.marketValue.convert(fx) } }
        val usCostInKrw = us?.let { fxUsdKrw?.let { fx -> it.purchaseAmount.convert(fx) } }
        val isConvertible = us == null || usInKrw != null
        val totalValue =
            if (isConvertible) sum(Currency.KRW, listOfNotNull(krw?.marketValue, usInKrw)) else null
        val totalCost =
            if (isConvertible) sum(Currency.KRW, listOfNotNull(krw?.purchaseAmount, usCostInKrw))
            else null
        val totalProfit = totalValue?.minus(totalCost!!)
        return PortfolioValuation(
            userId = userId,
            byMarket = byMarket,
            totalMarketValueKrw = totalValue,
            totalProfitLossKrw = totalProfit,
            totalReturnRate = returnRateOf(totalProfit, totalCost),
            fxUsdKrw = fxUsdKrw,
            cashBuyingPower = cashBuyingPower,
            isDelayed = isDelayed,
            asOf = asOf,
        )
    }

    private fun valuate(
        holding: BrokerHolding,
        quotes: Map<Symbol, Money>,
        previousCloses: Map<Symbol, Money>,
    ): HoldingValuation {
        val last = quotes[holding.symbol] ?: holding.lastPrice
        val marketValue = last.times(holding.quantity)
        val previousClose = previousCloses[holding.symbol]
        return HoldingValuation(
            symbol = holding.symbol,
            quantity = holding.quantity,
            lastPrice = last,
            purchaseAmount = holding.purchaseAmount,
            marketValue = marketValue,
            profitLoss = marketValue.minus(holding.purchaseAmount),
            previousClose = previousClose,
            todayChange = previousClose?.let { last.minus(it).times(holding.quantity) },
        )
    }

    private fun marketValuation(market: Market, holdings: List<HoldingValuation>): MarketValuation {
        val currency = market.currency
        val marketValue = sum(currency, holdings.map { it.marketValue })
        val purchaseAmount = sum(currency, holdings.map { it.purchaseAmount })
        val profitLoss = marketValue.minus(purchaseAmount)
        val changes = holdings.mapNotNull { it.todayChange }
        return MarketValuation(
            market = market,
            marketValue = marketValue,
            purchaseAmount = purchaseAmount,
            profitLoss = profitLoss,
            returnRate = returnRateOf(profitLoss, purchaseAmount),
            todayChange = if (changes.size == holdings.size) sum(currency, changes) else null,
            risers = changes.count { it.isPositive },
            fallers = changes.count { it.isNegative },
            holdings = holdings,
        )
    }

    // 매수금액이 0이면(무상 취득 등) 수익률을 정의하지 않음
    private fun returnRateOf(profit: Money?, cost: Money?): Percent? =
        if (profit == null || cost == null || cost.isZero) null else profit.ratioTo(cost)

    private fun sum(currency: Currency, amounts: List<Money>): Money =
        amounts.fold(Money.zero(currency)) { total, amount -> total.plus(amount) }

    private fun requireUsdKrw(fx: ExchangeRate?) {
        if (fx != null && (fx.from != Currency.USD || fx.to != Currency.KRW))
            throw InvalidValueException("평가 환산 환율은 USD→KRW 여야 함: ${fx.from}→${fx.to}")
    }
}
