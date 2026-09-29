package banghak.stock.support.fakes

import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.guardrail.GuardrailVerdict
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.market.TradingDay
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.portfolio.BrokerHoldings
import banghak.stock.core.domain.portfolio.Lot
import banghak.stock.core.domain.trading.BrokerOrder
import banghak.stock.core.domain.trading.BrokerOrderRecord
import banghak.stock.core.domain.trading.CandleInterval
import banghak.stock.core.domain.trading.CandlePage
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.ClosedOrdersPage
import banghak.stock.core.domain.trading.ClosedOrdersQuery
import banghak.stock.core.domain.trading.OrderAmendRequest
import banghak.stock.core.domain.trading.OrderBook
import banghak.stock.core.domain.trading.OrderIntent
import banghak.stock.core.domain.trading.OrderReceipt
import banghak.stock.core.domain.trading.OrderSubmission
import banghak.stock.core.domain.trading.Quote
import banghak.stock.core.domain.trading.SubmissionRecord
import banghak.stock.core.domain.trading.SubmissionState
import banghak.stock.core.port.BrokerOrderStorePort
import banghak.stock.core.port.LotStorePort
import banghak.stock.core.port.MarketCalendarPort
import banghak.stock.core.port.MarketDataPort
import banghak.stock.core.port.SubmissionStorePort
import banghak.stock.core.port.TradingPort
import banghak.stock.core.usecase.EvaluateGuardrailUseCase
import java.time.Instant
import java.time.LocalDate

/**
 * 주문을 실제로 내지 않는 증권사.
 * 접수 결과는 [placeResults] 에 차례로 넣은 값(접수 번호 또는 던질 예외)을 씀.
 */
class FakeTradingPort : TradingPort {
    val submissions = mutableListOf<OrderSubmission>()
    val placeResults = ArrayDeque<Any>()
    val openOrders = mutableListOf<BrokerOrderRecord>()
    val closedOrders = mutableListOf<BrokerOrderRecord>()
    var orderListReads = 0
    val buyingPower = mutableMapOf<Currency, Money>()
    var accountFailure: RuntimeException? = null

    override fun placeOrder(submission: OrderSubmission): OrderReceipt {
        submissions += submission
        return when (val next = placeResults.removeFirstOrNull() ?: "B-${submissions.size}") {
            is RuntimeException -> throw next
            is String -> OrderReceipt(next, submission.clientOrderId)
            else -> error("알 수 없는 접수 결과: $next")
        }
    }

    override fun openOrders(userId: UserId, market: Market): List<BrokerOrderRecord> {
        accountFailure?.let { throw it }
        orderListReads++
        return openOrders.filter { it.symbol.market == market }
    }

    override fun closedOrders(userId: UserId, query: ClosedOrdersQuery): ClosedOrdersPage {
        accountFailure?.let { throw it }
        orderListReads++
        return ClosedOrdersPage(
            closedOrders.filter { it.symbol.market == query.market },
            nextCursor = null,
        )
    }

    override fun buyingPower(userId: UserId, currency: Currency): Money {
        accountFailure?.let { throw it }
        return buyingPower[currency] ?: Money.zero(currency)
    }

    override fun placeAmendment(request: OrderAmendRequest): OrderReceipt = unused()

    override fun cancelOrder(userId: UserId, brokerOrderId: String): OrderReceipt = unused()

    override fun lookupOrder(userId: UserId, brokerOrderId: String): BrokerOrderRecord = unused()

    override fun holdings(userId: UserId): BrokerHoldings = unused()

    private fun unused(): Nothing = error("이 테스트에서 쓰지 않음")
}

/**
 * 현재가·환율을 손으로 채우는 시세 포트.
 * 없는 값은 시세 없음으로 던짐.
 */
class FakeMarketData : MarketDataPort {
    val quotes = mutableMapOf<Symbol, Quote>()
    val rates = mutableMapOf<Pair<Currency, Currency>, ExchangeRate>()

    override fun quotes(symbols: List<Symbol>): List<Quote> = symbols.mapNotNull { quotes[it] }

    override fun exchangeRate(from: Currency, to: Currency): ExchangeRate =
        rates[from to to] ?: throw MarketDataUnavailableException("환율 없음")

    override fun candlePage(
        symbol: Symbol,
        interval: CandleInterval,
        before: Instant?,
        count: Int,
    ): CandlePage = error("이 테스트에서 쓰지 않음")

    override fun orderBook(symbol: Symbol): OrderBook = error("이 테스트에서 쓰지 않음")
}

class FakeMarketCalendar(private val days: Map<Market, TradingDay>) : MarketCalendarPort {
    val requestedDates = mutableListOf<Pair<Market, LocalDate>>()

    override fun tradingDay(market: Market, date: LocalDate): TradingDay {
        requestedDates += market to date
        return days.getValue(market)
    }
}

class MemoryBrokerOrderStore : BrokerOrderStorePort {
    val orders = mutableMapOf<String, BrokerOrder>()
    val highValueConfirmed = mutableMapOf<String, Boolean>()

    override fun recordAccepted(order: BrokerOrder, isHighValueConfirmed: Boolean) {
        orders[order.brokerOrderId] = order
        highValueConfirmed[order.brokerOrderId] = isHighValueConfirmed
    }

    override fun findPlacedSince(
        userId: UserId,
        market: Market,
        since: Instant,
    ): List<BrokerOrder> =
        orders.values.filter {
            it.intent.userId == userId &&
                it.intent.market == market &&
                !it.updatedAt.isBefore(since)
        }
}

class MemorySubmissionStore : SubmissionStorePort {
    val records = mutableMapOf<ClientOrderId, SubmissionRecord>()

    override fun tryBegin(record: SubmissionRecord): Boolean =
        records.putIfAbsent(record.clientOrderId, record) == null

    override fun find(userId: UserId, clientOrderId: ClientOrderId): SubmissionRecord? =
        records[clientOrderId]?.takeIf { it.submission.intent.userId == userId }

    override fun markAccepted(
        userId: UserId,
        clientOrderId: ClientOrderId,
        brokerOrderId: String,
        at: Instant,
    ) =
        change(clientOrderId) {
            it.copy(state = SubmissionState.ACCEPTED, brokerOrderId = brokerOrderId)
        }

    override fun markResolved(
        userId: UserId,
        clientOrderId: ClientOrderId,
        state: SubmissionState,
        reason: String,
        at: Instant,
    ) = change(clientOrderId) { it.copy(state = state, reason = reason) }

    override fun markUnknown(
        userId: UserId,
        clientOrderId: ClientOrderId,
        reason: String,
        at: Instant,
    ) = change(clientOrderId) { it.copy(state = SubmissionState.UNKNOWN, reason = reason) }

    override fun findUnresolved(userId: UserId): List<SubmissionRecord> =
        records.values.filter { it.submission.intent.userId == userId && !it.state.isResolved }

    override fun claimedBrokerOrderIds(userId: UserId): Set<String> =
        records.values
            .filter { it.submission.intent.userId == userId }
            .mapNotNull { it.brokerOrderId }
            .toSet()

    private fun change(
        clientOrderId: ClientOrderId,
        update: (SubmissionRecord) -> SubmissionRecord,
    ) {
        records[clientOrderId] = update(records.getValue(clientOrderId))
    }
}

class MemoryLotStore : LotStorePort {
    val lots = mutableListOf<Lot>()

    override fun openLots(userId: UserId, market: Market): List<Lot> = lots.filter {
        it.userId == userId && it.symbol.market == market && it.isOpen
    }
}

/**
 * 정해 둔 판정을 돌려주는 가드레일.
 * 받은 입력을 기록함.
 */
class ScriptedGuardrail(var verdict: GuardrailVerdict = GuardrailVerdict.Passed(emptyList())) :
    EvaluateGuardrailUseCase {
    val evaluated = mutableListOf<Pair<OrderIntent, ClientOrderId>>()

    override fun evaluate(intent: OrderIntent, clientOrderId: ClientOrderId): GuardrailVerdict {
        evaluated += intent to clientOrderId
        return verdict
    }
}
