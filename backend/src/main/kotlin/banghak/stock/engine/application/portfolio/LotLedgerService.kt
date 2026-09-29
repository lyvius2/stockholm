package banghak.stock.engine.application.portfolio

import banghak.stock.core.domain.error.DomainException
import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.portfolio.BrokerHolding
import banghak.stock.core.domain.portfolio.BuyOrigin
import banghak.stock.core.domain.portfolio.FifoLotMatcher
import banghak.stock.core.domain.portfolio.FillState
import banghak.stock.core.domain.portfolio.Lot
import banghak.stock.core.domain.portfolio.LotId
import banghak.stock.core.domain.portfolio.OpeningLedger
import banghak.stock.core.domain.portfolio.OpeningPosition
import banghak.stock.core.domain.portfolio.QueuedFill
import banghak.stock.core.domain.portfolio.Sale
import banghak.stock.core.domain.portfolio.ShortageReconciliation
import banghak.stock.core.domain.trading.FillIncrement
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.port.DevicePort
import banghak.stock.core.port.FillQueuePort
import banghak.stock.core.port.LotLedgerPort
import banghak.stock.core.port.LotStorePort
import banghak.stock.core.port.MarketDataPort
import banghak.stock.core.port.TradingPort
import banghak.stock.core.port.UserAccountPort
import banghak.stock.core.usecase.ProcessFillsUseCase
import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.crypto.UlidGenerator
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 체결 대기열을 lot 에 반영함.
 * 같은 종목은 체결 순서대로만 반영하므로, 한 건이 막히면 그 종목의 뒤 체결도 기다림(선입선출이 어긋나지 않게).
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class LotLedgerService(
    private val fills: FillQueuePort,
    private val ledger: LotLedgerPort,
    private val lots: LotStorePort,
    private val trading: TradingPort,
    private val marketData: MarketDataPort,
    private val users: UserAccountPort,
    private val devices: DevicePort,
    private val journal: LotJournal,
    private val ulids: UlidGenerator,
    private val clock: Clock,
) : ProcessFillsUseCase {
    private val lastHoldingsRead = ConcurrentHashMap<UserId, Instant>()
    private val gapCandidates = ConcurrentHashMap<Pair<UserId, Symbol>, GapObservation>()

    override fun processFills() {
        users.findAll().forEach { account ->
            try {
                process(account.userId)
            } catch (e: RuntimeException) {
                log.warn("체결 반영 실패({}). 다음에 다시 함", e::class.simpleName)
            }
        }
    }

    // 원장은 첫 체결이 들어올 때 시작함.
    // 시작할 때 건너뛸 체결이 정해지므로 대기열은 시작 뒤에 다시 읽음
    private fun process(userId: UserId) {
        if (fills.unprocessed(userId).isEmpty()) return
        val device = devices.localDevice()
        if (ledger.startedAt(userId) == null && !tryStartLedger(userId, device)) return
        val waiting = mutableSetOf<Symbol>()
        val short = mutableSetOf<Symbol>()
        fills.unprocessed(userId).forEach { item ->
            if (item.fill.symbol in waiting) return@forEach
            when (apply(item, device)) {
                Outcome.APPLIED -> Unit
                Outcome.DEFERRED -> waiting += item.fill.symbol
                Outcome.SHORT -> {
                    waiting += item.fill.symbol
                    short += item.fill.symbol
                }
            }
        }
        if (short.isNotEmpty()) reconcileShortages(userId, device, short)
    }

    private enum class Outcome {
        APPLIED,
        DEFERRED,
        SHORT,
    }

    private fun apply(item: QueuedFill, device: DeviceId): Outcome {
        val fill = item.fill
        val fx =
            try {
                krwRateAt(fill.symbol.market, fill.executedAt)
            } catch (e: MarketDataUnavailableException) {
                log.info("체결 시각 환율을 받지 못해 lot 반영을 미룸")
                return Outcome.DEFERRED
            }
        return when (fill.side) {
            OrderSide.BUY -> {
                journal.recordBuy(item, lotOf(fill, fx), device)
                Outcome.APPLIED
            }
            OrderSide.SELL -> recordSell(item, fx, device)
        }
    }

    // lot 이 모자라면(기록 누락) 보류하고 보유 재대조를 기다림
    private fun recordSell(item: QueuedFill, fx: ExchangeRate?, device: DeviceId): Outcome {
        val fill = item.fill
        val open = lots.openLots(fill.userId, fill.symbol.market)
        val held = openQuantity(open, fill.symbol)
        if (fill.quantity.isGreaterThan(held)) {
            // 이미 막힘으로 남겼으면 다시 쓰지 않음(몇 초마다 같은 기록이 쌓이지 않게)
            if (item.state != FillState.BLOCKED)
                journal.mark(item, FillState.BLOCKED, "lot 부족: 기록된 보유 $held < 매도 ${fill.quantity}")
            return Outcome.SHORT
        }
        val sale =
            Sale(
                userId = fill.userId,
                brokerOrderId = fill.brokerOrderId,
                symbol = fill.symbol,
                quantity = fill.quantity,
                price = fill.unitPrice(),
                fee = fill.fee,
                tax = fill.tax,
                fxAtSell = fx,
                executedAt = fill.executedAt,
            )
        journal.recordSell(item, open, FifoLotMatcher.dispose(open, sale), device)
        return Outcome.APPLIED
    }

    // 보유 조회는 사용자마다 [RECONCILE_INTERVAL] 에 한 번만 함(사람 확인이 필요한 종목을 몇 초마다 묻지 않게).
    // 보정 후보는 따로 기억하므로 보정이 실패해도 다음 대조에서 다시 시도함.
    // 보유와 대기열을 한 시점에 읽을 수 없으므로, 조회 앞뒤로 대기열이 바뀌었으면 이번에는 판정하지 않음
    private fun reconcileShortages(userId: UserId, device: DeviceId, symbols: Set<Symbol>) {
        val now = clock.instant()
        if (lastHoldingsRead[userId]?.plus(RECONCILE_INTERVAL)?.isAfter(now) == true) return
        lastHoldingsRead[userId] = now
        val before = signatureOf(fills.unprocessed(userId))
        val holdings =
            try {
                trading.holdings(userId).items.associateBy { it.symbol }
            } catch (e: DomainException) {
                log.warn("lot 부족 재대조용 보유 조회 실패({}). 다음에 다시 함", e::class.simpleName)
                return
            }
        val queue = fills.unprocessed(userId)
        if (signatureOf(queue) != before) {
            log.info("보유 조회 중 체결 대기열이 바뀌어 이번에는 재대조하지 않음")
            return
        }
        symbols.forEach { symbol ->
            reconcile(
                userId,
                device,
                holdings[symbol],
                queue.filter { it.fill.symbol == symbol },
                now,
            )
        }
    }

    private fun reconcile(
        userId: UserId,
        device: DeviceId,
        holding: BrokerHolding?,
        pending: List<QueuedFill>,
        now: Instant,
    ) {
        val blocked = pending.firstOrNull()?.takeIf { it.state == FillState.BLOCKED } ?: return
        val symbol = blocked.fill.symbol
        val key = userId to symbol
        val recorded = openQuantity(lots.openLots(userId, symbol.market), symbol)
        when (val decision = ShortageReconciliation.decide(holding, recorded, pending)) {
            is ShortageReconciliation.Decision.FillGap ->
                confirmGap(
                    key,
                    GapObservation(
                        holding?.quantity,
                        recorded,
                        signatureOf(pending),
                        decision,
                        now,
                    ),
                    device,
                )
            is ShortageReconciliation.Decision.ApplyBuysFirst -> {
                gapCandidates.remove(key)
                pending.filter { it.id in decision.fillIds }.forEach { apply(it, device) }
            }
            is ShortageReconciliation.Decision.NeedsReview -> {
                gapCandidates.remove(key)
                val reason = "사람 확인 필요: ${decision.reason}"
                if (blocked.reason != reason) journal.mark(blocked, FillState.BLOCKED, reason)
            }
        }
    }

    // 빠진 매수로 보이는 차이는 되돌릴 근거 없이 lot 이 되므로, [GAP_CONFIRM_DELAY] 이상 떨어진 두 번의 대조에서
    // 같은 보유·기록·대기열을 연달아 볼 때만 채움.
    // 보유에는 들어갔는데 실시간 이벤트가 늦게 오는 체결이 있으면 그 사이 대기열이 바뀌어 채우지 않음
    private fun confirmGap(
        key: Pair<UserId, Symbol>,
        observation: GapObservation,
        device: DeviceId,
    ) {
        val previous = gapCandidates[key]
        if (previous == null || !previous.hasSameInputsAs(observation)) {
            gapCandidates[key] = observation
            return
        }
        if (Duration.between(previous.observedAt, observation.observedAt) < GAP_CONFIRM_DELAY)
            return
        journal.recordGapLot(gapLotOf(key.first, key.second, observation.decision), device)
        gapCandidates.remove(key)
        log.info("lot 부족을 보유 재대조로 채움")
    }

    private fun signatureOf(queue: List<QueuedFill>): List<Pair<String, FillState>> = queue.map {
        it.id to it.state
    }

    /** 빠진 매수 판정 한 번의 입력과 결과. */
    private data class GapObservation(
        val holding: Quantity?,
        val recorded: Quantity,
        val queue: List<Pair<String, FillState>>,
        val decision: ShortageReconciliation.Decision.FillGap,
        val observedAt: Instant,
    ) {
        fun hasSameInputsAs(other: GapObservation): Boolean =
            holding == other.holding &&
                recorded == other.recorded &&
                queue == other.queue &&
                decision == other.decision
    }

    // 빠진 매수는 실제 시각·환율을 몰라 기초 lot 으로 표시함
    private fun gapLotOf(
        userId: UserId,
        symbol: Symbol,
        gap: ShortageReconciliation.Decision.FillGap,
    ): Lot =
        Lot(
            id = LotId.from(ulids.next()),
            userId = userId,
            symbol = symbol,
            boughtQuantity = gap.quantity,
            remainingQuantity = gap.quantity,
            unitCost = gap.averagePrice,
            fxAtBuy = krwRateAt(symbol.market, gap.boughtAt),
            boughtAt = gap.boughtAt,
            origin = BuyOrigin.MANUAL,
            isOpening = true,
        )

    private fun openQuantity(open: List<Lot>, symbol: Symbol): Quantity =
        open
            .filter { it.symbol == symbol }
            .fold(Quantity.ZERO) { sum, lot -> sum.plus(lot.remainingQuantity) }

    private fun lotOf(fill: FillIncrement, fx: ExchangeRate?): Lot =
        Lot(
            id = LotId.from(ulids.next()),
            userId = fill.userId,
            symbol = fill.symbol,
            boughtQuantity = fill.quantity,
            remainingQuantity = fill.quantity,
            unitCost = fill.unitPrice(),
            fxAtBuy = fx,
            boughtAt = fill.executedAt,
            origin = BuyOrigin.of(fill.orderOrigin),
        )

    // 보유를 먼저 받고 대기열을 그 뒤에 읽어, 그 사이 들어온 체결도 대조에 넣음.
    // 체결 시각이 아니라 수량으로 기초 lot 을 정하므로 보유 조회가 방금 체결을 늦게 반영해도 체결이 빠지지 않음
    private fun tryStartLedger(userId: UserId, device: DeviceId): Boolean =
        try {
            val holdings = trading.holdings(userId).items.filterNot { it.quantity.isZero }
            val pending = fills.unprocessed(userId)
            val at = clock.instant()
            val plan = OpeningLedger.plan(holdings, pending)
            val openedAt =
                (pending.minOfOrNull { it.fill.executedAt } ?: at).minusMillis(
                    OPENING_LOT_OFFSET_MILLIS
                )
            val opening = plan.positions.map { openingLotOf(userId, it, openedAt, at) }
            journal.startLedger(
                userId,
                device,
                at,
                opening,
                pending.filter { it.id in plan.skippedFillIds },
            )
            log.info("lot 원장 시작: 기초 lot {}건, 건너뛴 이전 이력 {}건", opening.size, plan.skippedFillIds.size)
            true
        } catch (e: DomainException) {
            log.warn("lot 원장을 시작하지 못함({}). 다음에 다시 함", e::class.simpleName)
            false
        }

    // 기초 lot 은 실제 매수 시각·환율을 몰라, 대기열의 어떤 체결보다 앞선 시각과 원장 시작 때 환율로 둠(보유 기간은 보이지 않음)
    private fun openingLotOf(
        userId: UserId,
        position: OpeningPosition,
        openedAt: Instant,
        rateAt: Instant,
    ): Lot =
        Lot(
            id = LotId.from(ulids.next()),
            userId = userId,
            symbol = position.symbol,
            boughtQuantity = position.quantity,
            remainingQuantity = position.quantity,
            unitCost = position.averagePrice,
            fxAtBuy = krwRateAt(position.symbol.market, rateAt),
            boughtAt = openedAt,
            origin = BuyOrigin.MANUAL,
            isOpening = true,
        )

    private fun krwRateAt(market: Market, at: Instant): ExchangeRate? =
        if (market.currency == Currency.KRW) null
        else marketData.exchangeRateAt(market.currency, Currency.KRW, at)

    companion object {
        private val log = LoggerFactory.getLogger(LotLedgerService::class.java)

        private val RECONCILE_INTERVAL: Duration = Duration.ofMinutes(1)
        private val GAP_CONFIRM_DELAY: Duration = Duration.ofMinutes(1)

        // 기초 lot 이 대기열 체결로 생기는 lot 보다 먼저 소진되도록 가장 이른 체결보다 조금 앞에 둠
        private const val OPENING_LOT_OFFSET_MILLIS = 1L
    }
}
