package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.OrderIntent
import banghak.stock.core.domain.trading.OrderKind
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.OrderSubmission
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.SubmissionRecord
import banghak.stock.core.domain.trading.SubmissionState
import banghak.stock.core.domain.trading.TimeInForce
import banghak.stock.core.port.SubmissionStorePort
import banghak.stock.engine.adapter.out.persistence.entity.OrderSubmissionEntity
import banghak.stock.engine.adapter.out.persistence.repository.OrderSubmissionRepository
import banghak.stock.shared.config.RuntimeProfiles
import jakarta.persistence.EntityManager
import java.math.BigDecimal
import java.time.Instant
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaSubmissionStore(
    private val repository: OrderSubmissionRepository,
    private val entityManager: EntityManager,
) : SubmissionStorePort {
    // SQLite 쓰기 풀은 연결 하나라 쓰기 트랜잭션이 직렬화됨.
    // 조회 뒤 삽입이 두 요청 사이에 끼지 않음.
    // save()는 id 가 있는 엔티티를 merge 로 덮어쓰므로 persist 로 넣음
    @Transactional
    override fun tryBegin(record: SubmissionRecord): Boolean {
        val intent = record.submission.intent
        if (repository.findOwned(intent.userId.value, record.clientOrderId.value) != null)
            return false
        if (repository.existsById(record.clientOrderId.value)) return false
        entityManager.persist(rowOf(record))
        return true
    }

    @Transactional(readOnly = true)
    override fun find(userId: UserId, clientOrderId: ClientOrderId): SubmissionRecord? =
        repository.findOwned(userId.value, clientOrderId.value)?.let(::toDomain)

    @Transactional
    override fun markAccepted(
        userId: UserId,
        clientOrderId: ClientOrderId,
        brokerOrderId: String,
        at: Instant,
    ) =
        update(userId, clientOrderId, at) {
            it.state = SubmissionState.ACCEPTED.name
            it.brokerOrderId = brokerOrderId
        }

    @Transactional
    override fun markResolved(
        userId: UserId,
        clientOrderId: ClientOrderId,
        state: SubmissionState,
        reason: String,
        at: Instant,
    ) =
        update(userId, clientOrderId, at) {
            it.state = state.name
            it.reason = reason
        }

    @Transactional
    override fun markUnknown(
        userId: UserId,
        clientOrderId: ClientOrderId,
        reason: String,
        at: Instant,
    ) =
        update(userId, clientOrderId, at) {
            it.state = SubmissionState.UNKNOWN.name
            it.reason = reason
        }

    @Transactional(readOnly = true)
    override fun findUnresolved(userId: UserId): List<SubmissionRecord> =
        repository.findByUserIdAndStates(userId.value, UNRESOLVED_STATES).map(::toDomain)

    @Transactional(readOnly = true)
    override fun claimedBrokerOrderIds(userId: UserId): Set<String> =
        repository.findClaimedBrokerOrderIds(userId.value).toSet()

    private fun update(
        userId: UserId,
        clientOrderId: ClientOrderId,
        at: Instant,
        change: (OrderSubmissionEntity) -> Unit,
    ) {
        val row =
            repository.findOwned(userId.value, clientOrderId.value)
                ?: error("주문 요청 $clientOrderId 의 기록이 없음")
        change(row)
        row.updatedAt = at
    }

    private fun rowOf(record: SubmissionRecord): OrderSubmissionEntity {
        val intent = record.submission.intent
        return OrderSubmissionEntity(
            clientOrderId = record.clientOrderId.value,
            userId = intent.userId.value,
            deviceId = record.deviceId.value,
            market = intent.market.name,
            code = intent.symbol.code,
            side = intent.side.name,
            kind = intent.kind.name,
            timeInForce = intent.timeInForce.name,
            limitPriceAmount = intent.limitPrice?.amount,
            limitPriceCurrency = intent.limitPrice?.currency?.name,
            quantity = intent.quantity?.value,
            orderAmountAmount = intent.orderAmount?.amount,
            orderAmountCurrency = intent.orderAmount?.currency?.name,
            origin = intent.origin.name,
            triggerType = TriggerCodec.typeOf(intent.trigger),
            triggerJson = TriggerCodec.jsonOf(intent.trigger),
            intendedAt = intent.intendedAt,
            highValueConfirmed = record.submission.isHighValueConfirmed,
            state = record.state.name,
            brokerOrderId = record.brokerOrderId,
            reason = record.reason,
            sentAt = record.sentAt,
            updatedAt = record.sentAt,
            replacesBrokerOrderId = record.replacesBrokerOrderId,
        )
    }

    private fun toDomain(row: OrderSubmissionEntity): SubmissionRecord {
        val intent =
            OrderIntent(
                userId = UserId(row.userId),
                symbol = Symbol(Market.valueOf(row.market), row.code),
                side = OrderSide.valueOf(row.side),
                kind = OrderKind.valueOf(row.kind),
                timeInForce = TimeInForce.valueOf(row.timeInForce),
                limitPrice = moneyOf(row.limitPriceAmount, row.limitPriceCurrency),
                quantity = row.quantity?.let(Quantity::of),
                orderAmount = moneyOf(row.orderAmountAmount, row.orderAmountCurrency),
                origin = OrderOrigin.valueOf(row.origin),
                trigger = TriggerCodec.decode(row.triggerType, row.triggerJson),
                intendedAt = row.intendedAt,
            )
        return SubmissionRecord(
            submission =
                OrderSubmission(intent, ClientOrderId(row.clientOrderId), row.highValueConfirmed),
            deviceId = DeviceId(row.deviceId),
            state = SubmissionState.valueOf(row.state),
            brokerOrderId = row.brokerOrderId,
            reason = row.reason,
            sentAt = row.sentAt,
            replacesBrokerOrderId = row.replacesBrokerOrderId,
        )
    }

    private fun moneyOf(amount: BigDecimal?, currency: String?): Money? =
        if (amount == null || currency == null) null
        else Money.of(amount, Currency.valueOf(currency))

    companion object {
        private val UNRESOLVED_STATES =
            SubmissionState.entries.filterNot { it.isResolved }.map { it.name }
    }
}
