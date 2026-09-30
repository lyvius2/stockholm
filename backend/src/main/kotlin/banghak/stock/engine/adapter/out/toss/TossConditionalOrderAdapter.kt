package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.ConditionLeg
import banghak.stock.core.domain.trading.ConditionLegRecord
import banghak.stock.core.domain.trading.ConditionLegStatus
import banghak.stock.core.domain.trading.ConditionalOrderRecord
import banghak.stock.core.domain.trading.ConditionalOrderStatus
import banghak.stock.core.domain.trading.ConditionalOrderSubmission
import banghak.stock.core.domain.trading.ConditionalOrdersPage
import banghak.stock.core.domain.trading.ConditionalOrdersQuery
import banghak.stock.core.domain.trading.OrderKind
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.port.ConditionalOrderPort
import banghak.stock.shared.config.RuntimeProfiles
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.ratelimiter.annotation.RateLimiter
import java.math.BigDecimal
import java.time.LocalDate
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 토스 조건주문 어댑터.
 * 등록·수정·취소는 재시도하지 않고, 결과를 모르면 OrderResultUnknownException 으로 올려 목록 조회로 확정하게 함.
 * 서킷은 일반 주문과 같은 토스 주문 경로를 씀(같은 서버·같은 장애).
 * fallback 은 value class(UserId) 인자 때문에 JVM 이름이 바뀌지 않도록 final + @JvmName 으로 둠.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class TossConditionalOrderAdapter(
    private val orders: TossOrderClient,
    private val accountSeqs: TossAccountCache,
) : ConditionalOrderPort {
    @RateLimiter(name = "toss-conditional-order")
    @CircuitBreaker(name = "toss-order", fallbackMethod = "placeConditionalOrderFailed")
    override fun placeConditionalOrder(submission: ConditionalOrderSubmission): String {
        val intent = submission.intent
        val body =
            TossConditionalOrderRequest(
                symbol = intent.symbol.code,
                type = intent.type.name,
                quantity = intent.quantity.toString(),
                orderType = OrderKind.LIMIT.name,
                clientOrderId = submission.clientOrderId.value,
                expireDate = intent.expireDate.toString(),
                first = conditionOf(intent.first),
                second = intent.second?.let(::conditionOf),
                confirmHighValueOrder = confirmationOf(submission.isHighValueConfirmed),
            )
        return TossOrderResponses.mutationResultOf(
                orders.placeConditionalOrder(
                    TossCaller(intent.userId),
                    accountSeqs.accountSeq(intent.userId),
                    body,
                )
            )
            .conditionalOrderId
    }

    fun placeConditionalOrderFailed(
        submission: ConditionalOrderSubmission,
        cause: Throwable,
    ): String = TossOrderResponses.mutationFallback(cause)

    @RateLimiter(name = "toss-conditional-order")
    @CircuitBreaker(name = "toss-order", fallbackMethod = "placeConditionalAmendmentFailed")
    override fun placeConditionalAmendment(
        conditionalOrderId: String,
        submission: ConditionalOrderSubmission,
    ): String {
        val intent = submission.intent
        val body =
            TossConditionalModifyRequest(
                type = intent.type.name,
                quantity = intent.quantity.toString(),
                orderType = OrderKind.LIMIT.name,
                expireDate = intent.expireDate.toString(),
                first = conditionOf(intent.first),
                second = intent.second?.let(::conditionOf),
                confirmHighValueOrder = confirmationOf(submission.isHighValueConfirmed),
            )
        return TossOrderResponses.mutationResultOf(
                orders.modifyConditionalOrder(
                    TossCaller(intent.userId),
                    accountSeqs.accountSeq(intent.userId),
                    conditionalOrderId,
                    body,
                )
            )
            .conditionalOrderId
    }

    fun placeConditionalAmendmentFailed(
        conditionalOrderId: String,
        submission: ConditionalOrderSubmission,
        cause: Throwable,
    ): String = TossOrderResponses.mutationFallback(cause)

    @RateLimiter(name = "toss-conditional-order")
    @CircuitBreaker(name = "toss-order", fallbackMethod = "cancelConditionalOrderFailed")
    override fun cancelConditionalOrder(userId: UserId, conditionalOrderId: String) =
        TossOrderResponses.completionOf(
            orders.cancelConditionalOrder(
                TossCaller(userId),
                accountSeqs.accountSeq(userId),
                conditionalOrderId,
            )
        )

    @JvmName("cancelConditionalOrderFailed")
    final fun cancelConditionalOrderFailed(
        userId: UserId,
        conditionalOrderId: String,
        cause: Throwable,
    ): Unit = TossOrderResponses.mutationFallback(cause)

    @RateLimiter(name = "toss-conditional-order-history")
    @CircuitBreaker(name = "toss-order-read", fallbackMethod = "lookupConditionalOrderFailed")
    override fun lookupConditionalOrder(
        userId: UserId,
        conditionalOrderId: String,
    ): ConditionalOrderRecord =
        recordOf(
            TossOrderResponses.readResultOf(
                orders.conditionalOrder(
                    TossCaller(userId),
                    accountSeqs.accountSeq(userId),
                    conditionalOrderId,
                )
            )
        )

    @JvmName("lookupConditionalOrderFailed")
    final fun lookupConditionalOrderFailed(
        userId: UserId,
        conditionalOrderId: String,
        cause: Throwable,
    ): ConditionalOrderRecord = TossOrderResponses.readFallback(cause)

    @RateLimiter(name = "toss-conditional-order-history")
    @CircuitBreaker(name = "toss-order-read", fallbackMethod = "conditionalOrdersFailed")
    override fun conditionalOrders(
        userId: UserId,
        query: ConditionalOrdersQuery,
    ): ConditionalOrdersPage {
        val page =
            TossOrderResponses.readResultOf(
                orders.conditionalOrders(
                    TossCaller(userId),
                    accountSeqs.accountSeq(userId),
                    query.scope.name,
                    query.symbol?.code,
                    query.cursor,
                    PAGE_SIZE,
                )
            )
        val records =
            page.conditionalOrders.map(::recordOf).filter {
                query.symbol == null || it.symbol == query.symbol
            }
        return ConditionalOrdersPage(records, page.nextCursor?.takeIf { page.hasNext })
    }

    @JvmName("conditionalOrdersFailed")
    final fun conditionalOrdersFailed(
        userId: UserId,
        query: ConditionalOrdersQuery,
        cause: Throwable,
    ): ConditionalOrdersPage = TossOrderResponses.readFallback(cause)

    private fun conditionOf(leg: ConditionLeg) =
        TossConditionRequest(
            orderSide = leg.side.name,
            triggerPrice = leg.triggerPrice.amount.toPlainString(),
            orderPrice = leg.orderPrice.amount.toPlainString(),
        )

    private fun confirmationOf(isConfirmed: Boolean): Boolean? = if (isConfirmed) true else null

    private fun recordOf(order: TossConditionalOrder): ConditionalOrderRecord {
        val market = enumOf<Market>(order.market, "시장")
        return ConditionalOrderRecord(
            conditionalOrderId = order.conditionalOrderId,
            type = enumOf(order.type, "조건주문 타입"),
            status = enumOf<ConditionalOrderStatus>(order.status, "조건주문 상태"),
            symbol = Symbol(market, order.symbol),
            quantity = Quantity.of(order.quantity),
            kind = enumOf(order.orderType, "호가 유형"),
            expireDate = order.expireDate?.let(LocalDate::parse),
            first = legOf(order.first, market),
            second = order.second?.let { legOf(it, market) },
            createdAt = TossOrderMapping.parse(order.createdAt),
        )
    }

    // 수익률(PROFIT_RATE) 등 가격이 아닌 조건은 앱에서만 등록되며 감시가가 없을 수 있음
    private fun legOf(condition: TossCondition, market: Market) =
        ConditionLegRecord(
            isPriceTrigger = condition.type == PRICE_TRIGGER,
            status = enumOf<ConditionLegStatus>(condition.status, "감시 조건 상태"),
            triggerPrice = priceOf(condition.triggerPrice, market),
            orderPrice = priceOf(condition.orderPrice, market),
            triggeredOrderId = condition.triggeredOrderId,
        )

    private fun priceOf(amount: BigDecimal?, market: Market): Money? = amount?.let {
        Money.of(it, market.currency)
    }

    private inline fun <reified E : Enum<E>> enumOf(text: String, label: String): E =
        enumValues<E>().firstOrNull { it.name == text }
            ?: throw BrokerUnavailableException("토스가 모르는 $label 값을 줌: $text")

    companion object {
        private const val PRICE_TRIGGER = "STOP"

        // 토스 조건주문 목록 한 쪽의 최대 건수
        private const val PAGE_SIZE = 100
    }
}
