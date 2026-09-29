package banghak.stock.engine.application.portfolio

import banghak.stock.core.domain.error.DomainException
import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.portfolio.BuyOrigin
import banghak.stock.core.domain.portfolio.FifoLotMatcher
import banghak.stock.core.domain.portfolio.FillState
import banghak.stock.core.domain.portfolio.Lot
import banghak.stock.core.domain.portfolio.LotId
import banghak.stock.core.domain.portfolio.OpeningLedger
import banghak.stock.core.domain.portfolio.OpeningPosition
import banghak.stock.core.domain.portfolio.QueuedFill
import banghak.stock.core.domain.portfolio.Sale
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
import java.time.Instant
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
        fills.unprocessed(userId).forEach { item ->
            if (item.fill.symbol in waiting) return@forEach
            if (!apply(item, device)) waiting += item.fill.symbol
        }
    }

    /** 반영했으면 true, 막히거나 미뤘으면 false. */
    private fun apply(item: QueuedFill, device: DeviceId): Boolean {
        val fill = item.fill
        val fx =
            try {
                krwRateAt(fill.symbol.market, fill.executedAt)
            } catch (e: MarketDataUnavailableException) {
                log.info("체결 시각 환율을 받지 못해 lot 반영을 미룸")
                return false
            }
        return when (fill.side) {
            OrderSide.BUY -> {
                journal.recordBuy(item, lotOf(fill, fx), device)
                true
            }
            OrderSide.SELL -> recordSell(item, fx, device)
        }
    }

    // lot 이 모자라면(기록 누락) 보류하고 재대조를 기다림
    private fun recordSell(item: QueuedFill, fx: ExchangeRate?, device: DeviceId): Boolean {
        val fill = item.fill
        val open = lots.openLots(fill.userId, fill.symbol.market)
        val held =
            open
                .filter { it.symbol == fill.symbol }
                .fold(Quantity.ZERO) { sum, lot -> sum.plus(lot.remainingQuantity) }
        if (fill.quantity.isGreaterThan(held)) {
            // 이미 막힘으로 남겼으면 다시 쓰지 않음(몇 초마다 같은 기록이 쌓이지 않게)
            if (item.state != FillState.BLOCKED)
                journal.mark(item, FillState.BLOCKED, "lot 부족: 기록된 보유 $held < 매도 ${fill.quantity}")
            return false
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
        return true
    }

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

        // 기초 lot 이 대기열 체결로 생기는 lot 보다 먼저 소진되도록 가장 이른 체결보다 조금 앞에 둠
        private const val OPENING_LOT_OFFSET_MILLIS = 1L
    }
}
