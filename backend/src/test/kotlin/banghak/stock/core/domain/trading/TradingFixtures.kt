package banghak.stock.core.domain.trading

import banghak.stock.core.domain.automation.StrategyId
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import java.time.Instant
import java.time.LocalDate

/** 주문 테스트 공용 재료. */
object TradingFixtures {
    val now: Instant = Instant.parse("2026-09-28T01:00:00Z")
    val ulid: Ulid = Ulid.of(now, ByteArray(10))
    val user: UserId = UserId.from(ulid)
    val device: DeviceId = DeviceId.from(ulid)
    val samsung: Symbol = Symbol(Market.KR, "005930")
    val nvidia: Symbol = Symbol(Market.US, "NVDA")
    val manual: ManualTrigger = ManualTrigger(device)
    val autoBuy: AutoBuyTrigger =
        AutoBuyTrigger(StrategyId("momentum"), LocalDate.of(2026, 9, 28), 1)

    fun krw(amount: String): Money = Money.of(amount, Currency.KRW)

    fun usd(amount: String): Money = Money.of(amount, Currency.USD)

    fun limitBuy(
        symbol: Symbol = samsung,
        price: Money = krw("70000"),
        quantity: Quantity = Quantity.of(10),
        trigger: OrderTrigger = manual,
        origin: OrderOrigin = OrderOrigin.MANUAL,
        timeInForce: TimeInForce = TimeInForce.DAY,
    ): OrderIntent =
        OrderIntent(
            user,
            symbol,
            OrderSide.BUY,
            OrderKind.LIMIT,
            timeInForce,
            price,
            quantity,
            null,
            origin,
            trigger,
            now,
        )

    fun brokerRecord(
        intent: OrderIntent = limitBuy(),
        status: OrderStatus = OrderStatus.PENDING,
        filled: Quantity = Quantity.ZERO,
        brokerOrderId: String = "B-1",
    ): BrokerOrderRecord =
        BrokerOrderRecord(
            brokerOrderId = brokerOrderId,
            symbol = intent.symbol,
            side = intent.side,
            kind = intent.kind,
            timeInForce = intent.timeInForce,
            limitPrice = intent.limitPrice,
            quantity = intent.quantity,
            orderAmount = intent.orderAmount,
            status = status,
            filledQuantity = filled,
            averageFilledPrice = null,
            filledAmount = null,
            fee = null,
            tax = null,
            orderedAt = now,
            filledAt = null,
            canceledAt = null,
        )

    fun brokerOrder(
        intent: OrderIntent = limitBuy(),
        status: OrderStatus = OrderStatus.PENDING,
        filled: Quantity = Quantity.ZERO,
        brokerOrderId: String = "B-1",
    ): BrokerOrder =
        BrokerOrder(
            ClientOrderId.from(ulid),
            brokerOrderId,
            null,
            intent,
            status,
            filled,
            null,
            now,
        )
}
