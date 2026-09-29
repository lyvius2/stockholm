package banghak.stock.engine.application.guardrail

import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.guardrail.GuardrailContext
import banghak.stock.core.domain.guardrail.GuardrailFinding
import banghak.stock.core.domain.guardrail.GuardrailVerdict
import banghak.stock.core.domain.guardrail.ManualOrderGuardrails
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.portfolio.DepositBalance
import banghak.stock.core.domain.portfolio.PortfolioSnapshot
import banghak.stock.core.domain.portfolio.Position
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.OrderIntent
import banghak.stock.core.domain.trading.Quote
import banghak.stock.core.port.BrokerOrderStorePort
import banghak.stock.core.port.LotStorePort
import banghak.stock.core.port.MarketCalendarPort
import banghak.stock.core.port.MarketDataPort
import banghak.stock.core.port.TradingPort
import banghak.stock.core.usecase.EvaluateGuardrailUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 가드레일 컨텍스트를 모아 규칙을 돌림.
 * 계좌·미체결은 증권사에서 그 자리에서 받고, 받지 못하면 예외가 그대로 올라가 주문하지 않음.
 * 현재가·환율은 받지 못해도 null 로 두고 규칙이 판단함(고액 판정은 없으면 거부).
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class GuardrailService(
    private val trading: TradingPort,
    private val marketData: MarketDataPort,
    private val calendar: MarketCalendarPort,
    private val lots: LotStorePort,
    private val orders: BrokerOrderStorePort,
    private val clock: Clock,
) : EvaluateGuardrailUseCase {
    override fun evaluate(intent: OrderIntent, clientOrderId: ClientOrderId): GuardrailVerdict {
        // 자동 주문의 한도·노출액 규칙은 6단계에서 붙음.
        // 그 전에는 자동 주문을 모두 거부함
        if (intent.isAutomatic) return AUTOMATIC_NOT_READY
        return ManualOrderGuardrails.standard().evaluate(contextOf(intent, clientOrderId))
    }

    private fun contextOf(intent: OrderIntent, clientOrderId: ClientOrderId): GuardrailContext {
        val market = intent.market
        val snapshot = snapshotOf(intent.userId, market)
        val today = clock.instant().atZone(market.zone).toLocalDate()
        return GuardrailContext(
            intent = intent,
            clientOrderId = clientOrderId,
            snapshot = snapshot,
            todayOrders =
                orders.findPlacedSince(
                    intent.userId,
                    market,
                    today.atStartOfDay(market.zone).toInstant(),
                ),
            tradingDay = calendar.tradingDay(market, today),
            quote = quoteOf(intent.symbol),
            fxToKrw = if (market.currency == Currency.KRW) null else krwRateOf(market.currency),
            now = clock.instant(),
        )
    }

    // 스냅샷 시각은 조회를 시작한 순간으로 둬 신선도를 보수적으로 잼
    private fun snapshotOf(userId: UserId, market: Market): PortfolioSnapshot {
        val asOf = clock.instant()
        return PortfolioSnapshot(
            userId = userId,
            market = market,
            positions = Position.fromLots(userId, lots.openLots(userId, market)),
            deposit =
                DepositBalance(
                    mapOf(market.currency to trading.buyingPower(userId, market.currency)),
                    asOf,
                ),
            openOrders = trading.openOrders(userId, market),
            asOf = asOf,
        )
    }

    private fun quoteOf(symbol: Symbol): Quote? =
        try {
            marketData.quotes(listOf(symbol)).firstOrNull()
        } catch (e: MarketDataUnavailableException) {
            null
        }

    private fun krwRateOf(currency: Currency): ExchangeRate? =
        try {
            marketData.exchangeRate(currency, Currency.KRW)
        } catch (e: MarketDataUnavailableException) {
            null
        }

    companion object {
        private val AUTOMATIC_NOT_READY =
            GuardrailVerdict.Rejected(
                listOf(GuardrailFinding.Violation("AutomaticOrderRules", "자동 주문 가드레일이 아직 없음")),
                emptyList(),
            )
    }
}
