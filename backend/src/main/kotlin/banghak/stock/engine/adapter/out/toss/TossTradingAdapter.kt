package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.money.Percent
import banghak.stock.core.domain.portfolio.BrokerHolding
import banghak.stock.core.domain.portfolio.BrokerHoldings
import banghak.stock.core.domain.trading.BrokerOrderRecord
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.ClosedOrdersPage
import banghak.stock.core.domain.trading.ClosedOrdersQuery
import banghak.stock.core.domain.trading.CommissionRate
import banghak.stock.core.domain.trading.OrderAmendRequest
import banghak.stock.core.domain.trading.OrderKind
import banghak.stock.core.domain.trading.OrderReceipt
import banghak.stock.core.domain.trading.OrderSubmission
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.port.TradingPort
import banghak.stock.shared.config.RuntimeProfiles
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.ratelimiter.annotation.RateLimiter
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 토스 주문·계좌 어댑터.
 * 증권사가 아는 사실만 돌려주고, 출처·lot 과 합치는 일은 engine 서비스가 함.
 * 주문·정정·취소는 재시도하지 않음.
 * 결과를 모르면 OrderResultUnknownException 으로 올려 조회로 확정하게 함.
 * fallback 은 value class(UserId) 인자 때문에 JVM 이름이 바뀌지 않도록 final + @JvmName 으로 둠.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class TossTradingAdapter(
    private val orders: TossOrderClient,
    private val accounts: TossAccountClient,
    private val accountSeqs: TossAccountCache,
    private val clock: Clock,
) : TradingPort {
    @RateLimiter(name = "toss-order")
    @CircuitBreaker(name = "toss-order", fallbackMethod = "placeOrderFailed")
    override fun placeOrder(submission: OrderSubmission): OrderReceipt {
        val userId = submission.intent.userId
        val receipt =
            TossOrderResponses.receiptOf(
                orders.placeOrder(
                    TossCaller(userId),
                    accountSeqs.accountSeq(userId),
                    orderRequestOf(submission),
                )
            )
        return OrderReceipt(receipt.orderId, receipt.clientOrderId?.let(::ClientOrderId))
    }

    fun placeOrderFailed(submission: OrderSubmission, cause: Throwable): OrderReceipt =
        TossOrderResponses.mutationFallback(cause)

    @RateLimiter(name = "toss-order")
    @CircuitBreaker(name = "toss-order", fallbackMethod = "placeAmendmentFailed")
    override fun placeAmendment(request: OrderAmendRequest): OrderReceipt {
        val body =
            TossModifyRequest(
                orderType = OrderKind.LIMIT.name,
                quantity = request.quantity?.toString(),
                price = request.limitPrice.amount.toPlainString(),
                confirmHighValueOrder = confirmationOf(request.isHighValueConfirmed),
            )
        val receipt =
            TossOrderResponses.receiptOf(
                orders.modifyOrder(
                    TossCaller(request.userId),
                    accountSeqs.accountSeq(request.userId),
                    request.brokerOrderId,
                    body,
                )
            )
        return OrderReceipt(receipt.orderId, null)
    }

    fun placeAmendmentFailed(request: OrderAmendRequest, cause: Throwable): OrderReceipt =
        TossOrderResponses.mutationFallback(cause)

    @RateLimiter(name = "toss-order")
    @CircuitBreaker(name = "toss-order", fallbackMethod = "cancelOrderFailed")
    override fun cancelOrder(userId: UserId, brokerOrderId: String): OrderReceipt {
        val receipt =
            TossOrderResponses.receiptOf(
                orders.cancelOrder(
                    TossCaller(userId),
                    accountSeqs.accountSeq(userId),
                    brokerOrderId,
                    emptyMap(),
                )
            )
        return OrderReceipt(receipt.orderId, null)
    }

    @JvmName("cancelOrderFailed")
    final fun cancelOrderFailed(
        userId: UserId,
        brokerOrderId: String,
        cause: Throwable,
    ): OrderReceipt = TossOrderResponses.mutationFallback(cause)

    // 주문 상세는 ORDER_INFO 가 아니라 ORDER_HISTORY 그룹(초당 5)임
    @RateLimiter(name = "toss-order-history")
    @CircuitBreaker(name = "toss-order-read", fallbackMethod = "lookupOrderFailed")
    override fun lookupOrder(userId: UserId, brokerOrderId: String): BrokerOrderRecord =
        TossOrderMapping.recordOf(
            TossOrderResponses.readResultOf(
                orders.order(TossCaller(userId), accountSeqs.accountSeq(userId), brokerOrderId)
            )
        )

    @JvmName("lookupOrderFailed")
    final fun lookupOrderFailed(
        userId: UserId,
        brokerOrderId: String,
        cause: Throwable,
    ): BrokerOrderRecord = TossOrderResponses.readFallback(cause)

    // 토스 OPEN 목록은 시장 구분 없이 전량이 옴(커서 무시)
    @RateLimiter(name = "toss-order-history")
    @CircuitBreaker(name = "toss-order-read", fallbackMethod = "openOrdersFailed")
    override fun openOrders(userId: UserId, market: Market): List<BrokerOrderRecord> =
        TossOrderResponses.readResultOf(
                orders.orders(
                    TossCaller(userId),
                    accountSeqs.accountSeq(userId),
                    OPEN,
                    null,
                    null,
                    null,
                    null,
                )
            )
            .orders
            .map(TossOrderMapping::recordOf)
            .filter { it.symbol.market == market }

    @JvmName("openOrdersFailed")
    final fun openOrdersFailed(
        userId: UserId,
        market: Market,
        cause: Throwable,
    ): List<BrokerOrderRecord> = TossOrderResponses.readFallback(cause)

    @RateLimiter(name = "toss-order-history")
    @CircuitBreaker(name = "toss-order-read", fallbackMethod = "closedOrdersFailed")
    override fun closedOrders(userId: UserId, query: ClosedOrdersQuery): ClosedOrdersPage {
        val page =
            TossOrderResponses.readResultOf(
                orders.orders(
                    TossCaller(userId),
                    accountSeqs.accountSeq(userId),
                    CLOSED,
                    query.from.toString(),
                    query.to.toString(),
                    query.cursor,
                    CLOSED_PAGE_SIZE,
                )
            )
        return ClosedOrdersPage(
            page.orders.map(TossOrderMapping::recordOf).filter { it.symbol.market == query.market },
            page.nextCursor,
        )
    }

    @JvmName("closedOrdersFailed")
    final fun closedOrdersFailed(
        userId: UserId,
        query: ClosedOrdersQuery,
        cause: Throwable,
    ): ClosedOrdersPage = TossOrderResponses.readFallback(cause)

    @RateLimiter(name = "toss-asset")
    @CircuitBreaker(name = "toss-order-read", fallbackMethod = "holdingsFailed")
    override fun holdings(userId: UserId): BrokerHoldings {
        val result =
            TossOrderResponses.readResultOf(
                accounts.holdings(TossCaller(userId), accountSeqs.accountSeq(userId))
            )
        return BrokerHoldings(
            result.items.map(::holdingOf),
            Money.of(result.marketValue.amount.krw ?: BigDecimal.ZERO, Currency.KRW),
            clock.instant(),
        )
    }

    @JvmName("holdingsFailed")
    final fun holdingsFailed(userId: UserId, cause: Throwable): BrokerHoldings =
        TossOrderResponses.readFallback(cause)

    @RateLimiter(name = "toss-order-info")
    @CircuitBreaker(name = "toss-order-read", fallbackMethod = "buyingPowerFailed")
    override fun buyingPower(userId: UserId, currency: Currency): Money {
        val result =
            TossOrderResponses.readResultOf(
                accounts.buyingPower(
                    TossCaller(userId),
                    accountSeqs.accountSeq(userId),
                    currency.name,
                )
            )
        if (result.currency != currency.name)
            throw BrokerUnavailableException("$currency 매수 가능 금액을 요청했는데 ${result.currency} 가 옴")
        return Money.of(required(result.cashBuyingPower, "매수 가능 금액"), currency)
    }

    @JvmName("buyingPowerFailed")
    final fun buyingPowerFailed(userId: UserId, currency: Currency, cause: Throwable): Money =
        TossOrderResponses.readFallback(cause)

    @RateLimiter(name = "toss-order-info")
    @CircuitBreaker(name = "toss-order-read", fallbackMethod = "sellableQuantityFailed")
    override fun sellableQuantity(userId: UserId, symbol: Symbol): Quantity =
        Quantity.of(
            required(
                TossOrderResponses.readResultOf(
                        accounts.sellableQuantity(
                            TossCaller(userId),
                            accountSeqs.accountSeq(userId),
                            symbol.code,
                        )
                    )
                    .sellableQuantity,
                "판매 가능 수량",
            )
        )

    @JvmName("sellableQuantityFailed")
    final fun sellableQuantityFailed(userId: UserId, symbol: Symbol, cause: Throwable): Quantity =
        TossOrderResponses.readFallback(cause)

    // 모르는 시장의 수수료는 버림(토스는 모르는 값을 허용하라고 함)
    @RateLimiter(name = "toss-order-info")
    @CircuitBreaker(name = "toss-order-read", fallbackMethod = "commissionRatesFailed")
    override fun commissionRates(userId: UserId): List<CommissionRate> =
        TossOrderResponses.readResultOf(
                accounts.commissions(TossCaller(userId), accountSeqs.accountSeq(userId))
            )
            .mapNotNull { commission ->
                Market.entries
                    .firstOrNull { it.name == commission.marketCountry }
                    ?.let {
                        CommissionRate(
                            it,
                            Percent(required(commission.commissionRate, "수수료율")),
                            commission.startDate?.let(LocalDate::parse),
                            commission.endDate?.let(LocalDate::parse),
                        )
                    }
            }

    @JvmName("commissionRatesFailed")
    final fun commissionRatesFailed(userId: UserId, cause: Throwable): List<CommissionRate> =
        TossOrderResponses.readFallback(cause)

    // 금액 시장가(미국 금액 매수)는 유효 조건 없이 주문 금액만 보냄
    private fun orderRequestOf(submission: OrderSubmission): TossOrderRequest {
        val intent = submission.intent
        val isAmountOrder = intent.orderAmount != null
        return TossOrderRequest(
            clientOrderId = submission.clientOrderId.value,
            symbol = intent.symbol.code,
            side = intent.side.name,
            orderType = intent.kind.name,
            timeInForce = if (isAmountOrder) null else intent.timeInForce.name,
            quantity = intent.quantity?.toString(),
            price = intent.limitPrice?.amount?.toPlainString(),
            orderAmount = intent.orderAmount?.amount?.toPlainString(),
            confirmHighValueOrder = confirmationOf(submission.isHighValueConfirmed),
        )
    }

    private fun confirmationOf(isConfirmed: Boolean): Boolean? = if (isConfirmed) true else null

    // 성공 응답에 필수 값이 없으면 0 으로 읽지 않고 조회 실패로 올림
    private fun <T : Any> required(value: T?, label: String): T =
        value ?: throw BrokerUnavailableException("토스 응답에 $label 값이 없음")

    private fun holdingOf(item: TossHoldingItem): BrokerHolding {
        val symbol = Symbol(Market.valueOf(item.marketCountry), item.symbol)
        val currency = Currency.valueOf(item.currency)
        return BrokerHolding(
            symbol = symbol,
            quantity = Quantity.of(item.quantity),
            averagePurchasePrice = Money.of(item.averagePurchasePrice, currency),
            lastPrice = Money.of(item.lastPrice, currency),
            purchaseAmount = Money.of(item.marketValue.purchaseAmount, currency),
            marketValue = Money.of(item.marketValue.amount, currency),
            profitLoss = Money.of(item.profitLoss.amount, currency),
        )
    }

    companion object {
        private const val OPEN = "OPEN"
        private const val CLOSED = "CLOSED"
        private const val CLOSED_PAGE_SIZE = 100
    }
}
