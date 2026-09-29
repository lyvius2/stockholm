package banghak.stock.engine.application.portfolio

import banghak.stock.core.domain.eventlog.DomainEvent
import banghak.stock.core.domain.eventlog.LotClosed
import banghak.stock.core.domain.eventlog.LotOpened
import banghak.stock.core.domain.eventlog.LotReduced
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.portfolio.DisposalResult
import banghak.stock.core.domain.portfolio.FillState
import banghak.stock.core.domain.portfolio.Lot
import banghak.stock.core.domain.portfolio.QueuedFill
import banghak.stock.core.port.EventStore
import banghak.stock.core.port.FillQueuePort
import banghak.stock.core.port.LotLedgerPort
import banghak.stock.core.port.LotStorePort
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import java.time.Instant
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * lot 의 변화를 lot·소진 기록·이벤트·체결 대기열에 한 트랜잭션으로 남김.
 * 이벤트 추가와 projection 갱신이 같은 트랜잭션이라 둘이 어긋나지 않음.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class LotJournal(
    private val lots: LotStorePort,
    private val ledger: LotLedgerPort,
    private val fills: FillQueuePort,
    private val events: EventStore,
    private val clock: Clock,
) {
    /** 원장을 시작하고 기초 lot 을 남기며, 매입가를 알 수 없는 이전 이력은 건너뜀으로 표시함. */
    @Transactional
    fun startLedger(
        userId: UserId,
        deviceId: DeviceId,
        at: Instant,
        openingLots: List<Lot>,
        skipped: List<QueuedFill>,
    ) {
        openingLots.forEach { lots.saveOpened(it, brokerOrderId = null) }
        skipped.forEach { mark(it, FillState.SKIPPED, "원장 시작 전 이력이라 매입가를 알 수 없음") }
        ledger.start(userId, at)
        append(userId, deviceId, openingLots.map(::openedEventOf))
    }

    @Transactional
    fun recordBuy(item: QueuedFill, lot: Lot, deviceId: DeviceId) {
        lots.saveOpened(lot, item.fill.brokerOrderId)
        append(lot.userId, deviceId, listOf(openedEventOf(lot)))
        mark(item, FillState.DONE, reason = null)
    }

    /** [before] 는 매도 전 미청산 lot, [result] 는 선입선출 소진 결과임. */
    @Transactional
    fun recordSell(
        item: QueuedFill,
        before: List<Lot>,
        result: DisposalResult,
        deviceId: DeviceId,
    ) {
        val fill = item.fill
        val now = clock.instant()
        val changed = result.lotsAfter.filter { it !in before }
        lots.saveReduced(changed, fill.executedAt)
        lots.saveDisposals(fill.userId, fill.symbol, result.disposals, now)
        val reduced =
            result.disposals.map { LotReduced(it.lotId, it.quantity, it.sellPrice, it.disposedAt) }
        val closed = changed.filterNot { it.isOpen }.map { LotClosed(it.id, fill.executedAt) }
        append(fill.userId, deviceId, reduced + closed)
        mark(item, FillState.DONE, reason = null)
    }

    @Transactional
    fun mark(item: QueuedFill, state: FillState, reason: String?) {
        fills.mark(item.fill.userId, item.id, state, reason, clock.instant())
    }

    private fun openedEventOf(lot: Lot) =
        LotOpened(
            lot.id,
            lot.symbol,
            lot.boughtQuantity,
            lot.unitCost,
            lot.origin,
            lot.fxAtBuy,
            lot.boughtAt,
        )

    private fun append(userId: UserId, deviceId: DeviceId, list: List<DomainEvent>) {
        events.append(userId, deviceId, list, clock.instant())
    }
}
