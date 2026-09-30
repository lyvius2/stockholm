package banghak.stock.core.domain.trading

import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import java.time.Instant
import java.time.LocalDate

/**
 * 조건주문 테스트 공용 재료.
 * 기본값은 삼성전자 10주 OCO(익절 80,000 · 손절 60,000).
 */
object ConditionalFixtures {
    val expireDate: LocalDate = LocalDate.of(2026, 10, 30)

    fun leg(side: OrderSide, trigger: Money, price: Money = trigger) =
        ConditionLeg(side, trigger, price)

    fun oco(
        symbol: Symbol = TradingFixtures.samsung,
        quantity: Quantity = Quantity.of(10),
        takeProfit: Money = TradingFixtures.krw("80000"),
        stopLoss: Money = TradingFixtures.krw("60000"),
        expire: LocalDate = expireDate,
    ): ConditionalOrderIntent =
        ConditionalOrderIntent(
            TradingFixtures.user,
            symbol,
            ConditionalOrderType.OCO,
            quantity,
            leg(OrderSide.SELL, takeProfit),
            leg(OrderSide.SELL, stopLoss),
            expire,
            TradingFixtures.device,
            TradingFixtures.now,
        )

    fun single(
        side: OrderSide = OrderSide.BUY,
        trigger: Money = TradingFixtures.krw("65000"),
        price: Money = trigger,
        quantity: Quantity = Quantity.of(10),
        symbol: Symbol = TradingFixtures.samsung,
    ): ConditionalOrderIntent =
        ConditionalOrderIntent(
            TradingFixtures.user,
            symbol,
            ConditionalOrderType.SINGLE,
            quantity,
            leg(side, trigger, price),
            null,
            expireDate,
            TradingFixtures.device,
            TradingFixtures.now,
        )

    /**
     * 토스가 돌려주는 모양.
     * 매매 방향은 토스 응답에 없음.
     */
    fun record(
        intent: ConditionalOrderIntent = oco(),
        conditionalOrderId: String = "CO-1",
        status: ConditionalOrderStatus = ConditionalOrderStatus.WATCHING,
        createdAt: Instant = TradingFixtures.now,
    ): ConditionalOrderRecord =
        ConditionalOrderRecord(
            conditionalOrderId,
            intent.type,
            status,
            intent.symbol,
            intent.quantity,
            OrderKind.LIMIT,
            intent.expireDate,
            legRecordOf(intent.first),
            intent.second?.let(::legRecordOf),
            createdAt,
        )

    private fun legRecordOf(leg: ConditionLeg) =
        ConditionLegRecord(
            isPriceTrigger = true,
            status = ConditionLegStatus.WATCHING,
            triggerPrice = leg.triggerPrice,
            orderPrice = leg.orderPrice,
            triggeredOrderId = null,
        )
}
