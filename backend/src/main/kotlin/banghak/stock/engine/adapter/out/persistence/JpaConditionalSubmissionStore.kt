package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.ConditionLeg
import banghak.stock.core.domain.trading.ConditionalOrderIntent
import banghak.stock.core.domain.trading.ConditionalOrderSubmission
import banghak.stock.core.domain.trading.ConditionalOrderType
import banghak.stock.core.domain.trading.ConditionalSubmissionRecord
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.SubmissionState
import banghak.stock.core.port.ConditionalSubmissionStorePort
import banghak.stock.engine.adapter.out.persistence.entity.ConditionalSubmissionEntity
import banghak.stock.engine.adapter.out.persistence.repository.ConditionalSubmissionRepository
import banghak.stock.shared.config.RuntimeProfiles
import jakarta.persistence.EntityManager
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaConditionalSubmissionStore(
    private val repository: ConditionalSubmissionRepository,
    private val entityManager: EntityManager,
) : ConditionalSubmissionStorePort {
    // SQLite 쓰기 풀은 연결 하나라 쓰기 트랜잭션이 직렬화됨.
    // 조회 뒤 삽입이 두 요청 사이에 끼지 않음.
    // save()는 id 가 있는 엔티티를 merge 로 덮어쓰므로 persist 로 넣음
    @Transactional
    override fun tryBegin(record: ConditionalSubmissionRecord): Boolean {
        if (repository.existsById(record.clientOrderId.value)) return false
        entityManager.persist(rowOf(record))
        return true
    }

    @Transactional(readOnly = true)
    override fun find(userId: UserId, clientOrderId: ClientOrderId): ConditionalSubmissionRecord? =
        repository.findOwned(userId.value, clientOrderId.value)?.let(::toDomain)

    @Transactional
    override fun markAccepted(
        userId: UserId,
        clientOrderId: ClientOrderId,
        conditionalOrderId: String,
        at: Instant,
    ) =
        update(userId, clientOrderId, at) {
            it.state = SubmissionState.ACCEPTED.name
            it.conditionalOrderId = conditionalOrderId
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
    override fun findUnresolved(userId: UserId): List<ConditionalSubmissionRecord> =
        repository.findByUserIdAndStates(userId.value, UNRESOLVED_STATES).map(::toDomain)

    @Transactional(readOnly = true)
    override fun claimedConditionalOrderIds(userId: UserId): Set<String> =
        repository.findClaimedConditionalOrderIds(userId.value).toSet()

    private fun update(
        userId: UserId,
        clientOrderId: ClientOrderId,
        at: Instant,
        change: (ConditionalSubmissionEntity) -> Unit,
    ) {
        val row =
            repository.findOwned(userId.value, clientOrderId.value)
                ?: error("조건주문 요청 $clientOrderId 의 기록이 없음")
        change(row)
        row.updatedAt = at
    }

    private fun rowOf(record: ConditionalSubmissionRecord): ConditionalSubmissionEntity {
        val intent = record.submission.intent
        val second = intent.second
        return ConditionalSubmissionEntity(
            clientOrderId = record.clientOrderId.value,
            userId = intent.userId.value,
            deviceId = intent.requestedBy.value,
            market = intent.market.name,
            code = intent.symbol.code,
            type = intent.type.name,
            quantity = intent.quantity.value,
            currency = intent.market.currency.name,
            firstSide = intent.first.side.name,
            firstTriggerPrice = intent.first.triggerPrice.amount,
            firstOrderPrice = intent.first.orderPrice.amount,
            secondSide = second?.side?.name,
            secondTriggerPrice = second?.triggerPrice?.amount,
            secondOrderPrice = second?.orderPrice?.amount,
            expireDate = intent.expireDate.toString(),
            intendedAt = intent.intendedAt,
            highValueConfirmed = record.submission.isHighValueConfirmed,
            replacesConditionalOrderId = record.replacesConditionalOrderId,
            state = record.state.name,
            conditionalOrderId = record.conditionalOrderId,
            reason = record.reason,
            sentAt = record.sentAt,
            updatedAt = record.sentAt,
        )
    }

    private fun toDomain(row: ConditionalSubmissionEntity): ConditionalSubmissionRecord {
        val currency = Currency.valueOf(row.currency)
        val intent =
            ConditionalOrderIntent(
                userId = UserId(row.userId),
                symbol = Symbol(Market.valueOf(row.market), row.code),
                type = ConditionalOrderType.valueOf(row.type),
                quantity = Quantity.of(row.quantity),
                first = legOf(row.firstSide, row.firstTriggerPrice, row.firstOrderPrice, currency),
                second = secondLegOf(row, currency),
                expireDate = LocalDate.parse(row.expireDate),
                requestedBy = DeviceId(row.deviceId),
                intendedAt = row.intendedAt,
            )
        return ConditionalSubmissionRecord(
            submission =
                ConditionalOrderSubmission(
                    intent,
                    ClientOrderId(row.clientOrderId),
                    row.highValueConfirmed,
                ),
            state = SubmissionState.valueOf(row.state),
            conditionalOrderId = row.conditionalOrderId,
            reason = row.reason,
            sentAt = row.sentAt,
            replacesConditionalOrderId = row.replacesConditionalOrderId,
        )
    }

    private fun secondLegOf(row: ConditionalSubmissionEntity, currency: Currency): ConditionLeg? {
        val side = row.secondSide ?: return null
        return legOf(
            side,
            row.secondTriggerPrice ?: error("조건주문 요청 ${row.clientOrderId} 의 둘째 감시가가 없음"),
            row.secondOrderPrice ?: error("조건주문 요청 ${row.clientOrderId} 의 둘째 주문 가격이 없음"),
            currency,
        )
    }

    private fun legOf(
        side: String,
        triggerPrice: BigDecimal,
        orderPrice: BigDecimal,
        currency: Currency,
    ) =
        ConditionLeg(
            OrderSide.valueOf(side),
            Money.of(triggerPrice, currency),
            Money.of(orderPrice, currency),
        )

    companion object {
        private val UNRESOLVED_STATES =
            SubmissionState.entries.filterNot { it.isResolved }.map { it.name }
    }
}
