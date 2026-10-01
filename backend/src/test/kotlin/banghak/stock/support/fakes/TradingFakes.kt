package banghak.stock.support.fakes

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.guardrail.GuardrailVerdict
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.market.TradingDay
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.portfolio.BrokerHolding
import banghak.stock.core.domain.portfolio.BrokerHoldings
import banghak.stock.core.domain.portfolio.FillState
import banghak.stock.core.domain.portfolio.Lot
import banghak.stock.core.domain.portfolio.LotDisposal
import banghak.stock.core.domain.portfolio.LotId
import banghak.stock.core.domain.portfolio.QueuedFill
import banghak.stock.core.domain.trading.BrokerOrder
import banghak.stock.core.domain.trading.BrokerOrderRecord
import banghak.stock.core.domain.trading.Candle
import banghak.stock.core.domain.trading.CandleInterval
import banghak.stock.core.domain.trading.CandlePage
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.ClosedOrdersPage
import banghak.stock.core.domain.trading.ClosedOrdersQuery
import banghak.stock.core.domain.trading.CommissionRate
import banghak.stock.core.domain.trading.ConditionalOrderIntent
import banghak.stock.core.domain.trading.FeedTopic
import banghak.stock.core.domain.trading.FillIncrement
import banghak.stock.core.domain.trading.FillSummary
import banghak.stock.core.domain.trading.OrderAmendRequest
import banghak.stock.core.domain.trading.OrderBook
import banghak.stock.core.domain.trading.OrderIntent
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderProgress
import banghak.stock.core.domain.trading.OrderReceipt
import banghak.stock.core.domain.trading.OrderSubmission
import banghak.stock.core.domain.trading.PriceLimits
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.Quote
import banghak.stock.core.domain.trading.RecordedOrder
import banghak.stock.core.domain.trading.SubmissionRecord
import banghak.stock.core.domain.trading.SubmissionState
import banghak.stock.core.port.BrokerOrderStorePort
import banghak.stock.core.port.FeedListener
import banghak.stock.core.port.FillQueuePort
import banghak.stock.core.port.LotLedgerPort
import banghak.stock.core.port.LotStorePort
import banghak.stock.core.port.MarketCalendarPort
import banghak.stock.core.port.MarketDataPort
import banghak.stock.core.port.RealtimeFeedPort
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
    val closedQueries = mutableListOf<ClosedOrdersQuery>()
    var hasMoreClosedPages = false
    val details = mutableMapOf<String, BrokerOrderRecord>()
    val amendments = mutableListOf<OrderAmendRequest>()
    val amendResults = ArrayDeque<Any>()
    val cancels = mutableListOf<String>()
    val cancelResults = ArrayDeque<Any>()
    var orderListReads = 0
    val buyingPower = mutableMapOf<Currency, Money>()
    var accountFailure: RuntimeException? = null

    override fun placeOrder(submission: OrderSubmission): OrderReceipt {
        submissions += submission
        return receiptOf(
            placeResults.removeFirstOrNull() ?: "B-${submissions.size}",
            submission.clientOrderId,
        )
    }

    override fun openOrders(userId: UserId, market: Market): List<BrokerOrderRecord> {
        accountFailure?.let { throw it }
        orderListReads++
        return openOrders.filter { it.symbol.market == market }
    }

    override fun closedOrders(userId: UserId, query: ClosedOrdersQuery): ClosedOrdersPage {
        accountFailure?.let { throw it }
        orderListReads++
        closedQueries += query
        return ClosedOrdersPage(
            closedOrders.filter { it.symbol.market == query.market },
            nextCursor = if (hasMoreClosedPages) "next" else null,
        )
    }

    override fun buyingPower(userId: UserId, currency: Currency): Money {
        accountFailure?.let { throw it }
        return buyingPower[currency] ?: Money.zero(currency)
    }

    val sellable = mutableMapOf<Symbol, Quantity>()
    val commissions = mutableListOf<CommissionRate>()
    var commissionFailure: RuntimeException? = null

    override fun sellableQuantity(userId: UserId, symbol: Symbol): Quantity {
        accountFailure?.let { throw it }
        return sellable[symbol] ?: Quantity.ZERO
    }

    override fun commissionRates(userId: UserId): List<CommissionRate> {
        commissionFailure?.let { throw it }
        return commissions
    }

    override fun placeAmendment(request: OrderAmendRequest): OrderReceipt {
        amendments += request
        return receiptOf(amendResults.removeFirstOrNull() ?: "A-${amendments.size}", null)
    }

    override fun cancelOrder(userId: UserId, brokerOrderId: String): OrderReceipt {
        cancels += brokerOrderId
        return receiptOf(cancelResults.removeFirstOrNull() ?: "C-${cancels.size}", null)
    }

    private fun receiptOf(next: Any, clientOrderId: ClientOrderId?): OrderReceipt =
        when (next) {
            is RuntimeException -> throw next
            is String -> OrderReceipt(next, clientOrderId)
            else -> error("알 수 없는 결과: $next")
        }

    override fun lookupOrder(userId: UserId, brokerOrderId: String): BrokerOrderRecord {
        accountFailure?.let { throw it }
        return details[brokerOrderId] ?: throw InvalidValueException("토스에 없는 주문: $brokerOrderId")
    }

    val holdings = mutableListOf<BrokerHolding>()

    var holdingsReads = 0
    var onHoldingsRead: (() -> Unit)? = null

    override fun holdings(userId: UserId): BrokerHoldings {
        accountFailure?.let { throw it }
        holdingsReads++
        onHoldingsRead?.invoke()
        return BrokerHoldings(holdings.toList(), Money.zero(Currency.KRW), Instant.EPOCH)
    }

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

    val rateRequests = mutableListOf<Instant>()

    // 지정한 환율을 요청 시각의 값으로 돌려줌
    override fun exchangeRateAt(from: Currency, to: Currency, at: Instant): ExchangeRate {
        rateRequests += at
        return rates[from to to]?.copy(asOf = at) ?: throw MarketDataUnavailableException("환율 없음")
    }

    val candles = mutableListOf<Candle>()
    var candlePageSize = MarketDataPort.MAX_CANDLES
    var candleReads = 0
    var candleFailure: RuntimeException? = null

    // 포트 계약대로 최신순으로 주고, 다음 위치는 남은 봉 중 가장 최근 봉의 시작 시각임
    override fun candlePage(
        symbol: Symbol,
        interval: CandleInterval,
        before: Instant?,
        count: Int,
    ): CandlePage {
        candleReads++
        candleFailure?.let { throw it }
        val matching =
            candles
                .filter { it.symbol == symbol && it.interval == interval }
                .filter { before == null || !it.openTime.isAfter(before) }
                .sortedByDescending { it.openTime }
        val size = minOf(count, candlePageSize)
        return CandlePage(matching.take(size), matching.getOrNull(size)?.openTime)
    }

    override fun orderBook(symbol: Symbol): OrderBook = error("이 테스트에서 쓰지 않음")

    val priceLimits = mutableMapOf<Symbol, PriceLimits>()

    var priceLimitsFailure: RuntimeException? = null

    override fun priceLimits(symbol: Symbol): PriceLimits {
        priceLimitsFailure?.let { throw it }
        return priceLimits[symbol] ?: throw MarketDataUnavailableException("상하한가 없음")
    }
}

class FakeMarketCalendar(private val days: Map<Market, TradingDay>) : MarketCalendarPort {
    val requestedDates = mutableListOf<Pair<Market, LocalDate>>()

    override fun tradingDay(market: Market, date: LocalDate): TradingDay {
        requestedDates += market to date
        return days.getValue(market)
    }
}

/**
 * 주문 기록.
 * 우리 주문(의도 포함)과 증권사가 알려 준 진행을 따로 둠.
 */
class MemoryBrokerOrderStore : BrokerOrderStorePort {
    val orders = mutableMapOf<String, BrokerOrder>()
    val highValueConfirmed = mutableMapOf<String, Boolean>()
    val progress = mutableMapOf<String, Pair<UserId, OrderProgress>>()
    val applied = mutableListOf<BrokerOrderRecord>()
    val applyFailures = ArrayDeque<RuntimeException>()

    override fun recordAccepted(order: BrokerOrder, isHighValueConfirmed: Boolean) {
        orders[order.brokerOrderId] = order
        highValueConfirmed[order.brokerOrderId] = isHighValueConfirmed
        progress.putIfAbsent(
            order.brokerOrderId,
            order.intent.userId to OrderProgress(order.status, order.filledQuantity),
        )
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

    val queued = mutableMapOf<String, FillSummary>()

    override fun findRecorded(userId: UserId, brokerOrderId: String): RecordedOrder? {
        val progress = findProgress(userId, brokerOrderId) ?: return null
        val currency =
            (orders[brokerOrderId]?.intent?.symbol
                    ?: applied.last { it.brokerOrderId == brokerOrderId }.symbol)
                .market
                .currency
        return RecordedOrder(
            progress = progress,
            isPlacedByStockholm = brokerOrderId in orders,
            origin = orders[brokerOrderId]?.intent?.origin ?: OrderOrigin.MANUAL,
            queuedFill = queued[brokerOrderId] ?: FillSummary.zero(currency),
        )
    }

    override fun markFillQueued(userId: UserId, brokerOrderId: String, queued: FillSummary) {
        this.queued[brokerOrderId] = queued
    }

    fun findProgress(userId: UserId, brokerOrderId: String): OrderProgress? =
        progress[brokerOrderId]?.takeIf { it.first == userId }?.second

    // 밖에서 낸 주문은 실시간 반영 때 수동 출처로 기록됨
    override fun findOrigin(userId: UserId, brokerOrderId: String): OrderOrigin? =
        orders[brokerOrderId]?.takeIf { it.intent.userId == userId }?.intent?.origin
            ?: progress[brokerOrderId]?.takeIf { it.first == userId }?.let { OrderOrigin.MANUAL }

    override fun applyBrokerRecord(userId: UserId, record: BrokerOrderRecord, at: Instant) {
        applyFailures.removeFirstOrNull()?.let { throw it }
        progress[record.brokerOrderId] =
            userId to OrderProgress(record.status, record.filledQuantity)
        applied += record
    }

    override fun openBrokerOrderIds(userId: UserId): Set<String> =
        progress.filterValues { (owner, state) -> owner == userId && state.status.isOpen }.keys
}

/**
 * 선언·해제를 기록하는 실시간 포트.
 * 리스너는 테스트가 직접 부름.
 */
class FakeRealtimeFeed : RealtimeFeedPort {
    val declared = mutableMapOf<UserId, Set<FeedTopic>>()
    val released = mutableListOf<UserId>()
    val listeners = mutableListOf<FeedListener>()

    override fun declare(owner: UserId, topics: Set<FeedTopic>) {
        declared[owner] = topics
    }

    override fun release(owner: UserId) {
        declared.remove(owner)
        released += owner
    }

    override fun addListener(listener: FeedListener) {
        listeners += listener
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
    val lotOrders = mutableMapOf<LotId, String?>()
    val disposals = mutableListOf<LotDisposal>()

    override fun openLots(userId: UserId, market: Market): List<Lot> = lots.filter {
        it.userId == userId && it.symbol.market == market && it.isOpen
    }

    val saveFailures = ArrayDeque<RuntimeException>()

    override fun saveOpened(lot: Lot, brokerOrderId: String?) {
        saveFailures.removeFirstOrNull()?.let { throw it }
        lots += lot
        lotOrders[lot.id] = brokerOrderId
    }

    override fun saveReduced(lots: List<Lot>, at: Instant) {
        lots.forEach { changed ->
            this.lots.replaceAll { if (it.id == changed.id) changed else it }
        }
    }

    override fun saveDisposals(
        userId: UserId,
        symbol: Symbol,
        disposals: List<LotDisposal>,
        at: Instant,
    ) {
        this.disposals += disposals
    }
}

class MemoryFillQueue : FillQueuePort {
    val items = mutableListOf<QueuedFill>()
    private var sequence = 0

    override fun enqueue(fill: FillIncrement, at: Instant) {
        items += QueuedFill("F-${++sequence}", fill, FillState.PENDING, null)
    }

    override fun unprocessed(userId: UserId): List<QueuedFill> =
        items
            .filter {
                it.fill.userId == userId && it.state in setOf(FillState.PENDING, FillState.BLOCKED)
            }
            .sortedBy { it.fill.executedAt }

    override fun mark(
        userId: UserId,
        fillId: String,
        state: FillState,
        reason: String?,
        at: Instant,
    ) {
        items.replaceAll { if (it.id == fillId) it.copy(state = state, reason = reason) else it }
    }

    fun stateOf(brokerOrderId: String): FillState =
        items.single { it.fill.brokerOrderId == brokerOrderId }.state
}

class MemoryLotLedger : LotLedgerPort {
    val started = mutableMapOf<UserId, Instant>()

    override fun startedAt(userId: UserId): Instant? = started[userId]

    override fun start(userId: UserId, at: Instant) {
        started.putIfAbsent(userId, at)
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

    val evaluatedConditional = mutableListOf<ConditionalOrderIntent>()

    override fun evaluateConditional(intent: ConditionalOrderIntent): GuardrailVerdict {
        evaluatedConditional += intent
        return verdict
    }
}
