package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.portfolio.FillState
import banghak.stock.core.domain.portfolio.QueuedFill
import banghak.stock.core.domain.trading.FillIncrement
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.port.FillQueuePort
import banghak.stock.core.port.LotLedgerPort
import banghak.stock.engine.adapter.out.persistence.entity.FillQueueEntity
import banghak.stock.engine.adapter.out.persistence.entity.LotLedgerEntity
import banghak.stock.engine.adapter.out.persistence.repository.FillQueueRepository
import banghak.stock.engine.adapter.out.persistence.repository.LotLedgerRepository
import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.crypto.UlidGenerator
import java.time.Instant
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaFillQueue(private val repository: FillQueueRepository, private val ulids: UlidGenerator) :
    FillQueuePort {
    @Transactional
    override fun enqueue(fill: FillIncrement, at: Instant) {
        repository.save(
            FillQueueEntity(
                fillId = ulids.next().value,
                userId = fill.userId.value,
                brokerOrderId = fill.brokerOrderId,
                market = fill.symbol.market.name,
                code = fill.symbol.code,
                side = fill.side.name,
                quantity = fill.quantity.value,
                amount = fill.amount.amount,
                fee = fill.fee.amount,
                tax = fill.tax.amount,
                currency = fill.amount.currency.name,
                orderOrigin = fill.orderOrigin.name,
                executedAt = fill.executedAt,
                state = FillState.PENDING.name,
                reason = null,
                createdAt = at,
                updatedAt = at,
            )
        )
    }

    @Transactional(readOnly = true)
    override fun unprocessed(userId: UserId): List<QueuedFill> =
        repository.findByUserIdAndStates(userId.value, UNPROCESSED).map(::toDomain)

    @Transactional
    override fun mark(
        userId: UserId,
        fillId: String,
        state: FillState,
        reason: String?,
        at: Instant,
    ) {
        val row = repository.findOwned(userId.value, fillId) ?: error("체결 대기열 $fillId 이 없음")
        row.state = state.name
        row.reason = reason
        row.updatedAt = at
    }

    private fun toDomain(row: FillQueueEntity): QueuedFill {
        val currency = Currency.valueOf(row.currency)
        return QueuedFill(
            id = row.fillId,
            fill =
                FillIncrement(
                    userId = UserId(row.userId),
                    brokerOrderId = row.brokerOrderId,
                    symbol = Symbol(Market.valueOf(row.market), row.code),
                    side = OrderSide.valueOf(row.side),
                    quantity = Quantity.of(row.quantity),
                    amount = Money.of(row.amount, currency),
                    fee = Money.of(row.fee, currency),
                    tax = Money.of(row.tax, currency),
                    orderOrigin = OrderOrigin.valueOf(row.orderOrigin),
                    executedAt = row.executedAt,
                ),
            state = FillState.valueOf(row.state),
            reason = row.reason,
        )
    }

    companion object {
        private val UNPROCESSED = listOf(FillState.PENDING.name, FillState.BLOCKED.name)
    }
}

@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaLotLedger(private val repository: LotLedgerRepository) : LotLedgerPort {
    @Transactional(readOnly = true)
    override fun startedAt(userId: UserId): Instant? =
        repository.findById(userId.value).orElse(null)?.startedAt

    @Transactional
    override fun start(userId: UserId, at: Instant) {
        if (repository.existsById(userId.value)) return
        repository.save(LotLedgerEntity(userId.value, at, at))
    }
}
