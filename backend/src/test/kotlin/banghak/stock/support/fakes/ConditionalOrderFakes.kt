package banghak.stock.support.fakes

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.ConditionalOrderRecord
import banghak.stock.core.domain.trading.ConditionalOrderScope
import banghak.stock.core.domain.trading.ConditionalOrderSubmission
import banghak.stock.core.domain.trading.ConditionalOrdersPage
import banghak.stock.core.domain.trading.ConditionalOrdersQuery
import banghak.stock.core.domain.trading.ConditionalSubmissionRecord
import banghak.stock.core.domain.trading.SubmissionState
import banghak.stock.core.port.ConditionalOrderPort
import banghak.stock.core.port.ConditionalSubmissionStorePort
import java.time.Instant

/**
 * 조건주문 포트 가짜.
 * 등록·수정·취소 결과는 순서대로 꺼내 쓰며, 문자열이면 번호로 돌려주고 예외면 던짐.
 */
class FakeConditionalOrderPort : ConditionalOrderPort {
    val registrations = mutableListOf<ConditionalOrderSubmission>()
    val registerResults = ArrayDeque<Any>()
    val amendments = mutableListOf<Pair<String, ConditionalOrderSubmission>>()
    val amendResults = ArrayDeque<Any>()
    val cancels = mutableListOf<String>()
    val cancelResults = ArrayDeque<RuntimeException>()
    val details = mutableMapOf<String, ConditionalOrderRecord>()
    val open = mutableListOf<ConditionalOrderRecord>()
    val closed = mutableListOf<ConditionalOrderRecord>()
    var hasMorePages = false
    val queries = mutableListOf<ConditionalOrdersQuery>()

    override fun placeConditionalOrder(submission: ConditionalOrderSubmission): String {
        registrations += submission
        return idOf(registerResults.removeFirstOrNull() ?: "CO-${registrations.size}")
    }

    override fun placeConditionalAmendment(
        conditionalOrderId: String,
        submission: ConditionalOrderSubmission,
    ): String {
        amendments += conditionalOrderId to submission
        return idOf(amendResults.removeFirstOrNull() ?: "CM-${amendments.size}")
    }

    override fun cancelConditionalOrder(userId: UserId, conditionalOrderId: String) {
        cancels += conditionalOrderId
        cancelResults.removeFirstOrNull()?.let { throw it }
    }

    override fun lookupConditionalOrder(
        userId: UserId,
        conditionalOrderId: String,
    ): ConditionalOrderRecord =
        details[conditionalOrderId]
            ?: throw InvalidValueException("토스에 없는 조건주문: $conditionalOrderId")

    override fun conditionalOrders(
        userId: UserId,
        query: ConditionalOrdersQuery,
    ): ConditionalOrdersPage {
        queries += query
        val source = if (query.scope == ConditionalOrderScope.OPEN) open else closed
        return ConditionalOrdersPage(
            source.filter { query.symbol == null || it.symbol == query.symbol },
            if (hasMorePages) "next" else null,
        )
    }

    private fun idOf(next: Any): String =
        when (next) {
            is RuntimeException -> throw next
            is String -> next
            else -> error("알 수 없는 결과: $next")
        }
}

class MemoryConditionalSubmissionStore : ConditionalSubmissionStorePort {
    private val records = linkedMapOf<ClientOrderId, ConditionalSubmissionRecord>()

    override fun tryBegin(record: ConditionalSubmissionRecord): Boolean {
        if (record.clientOrderId in records) return false
        records[record.clientOrderId] = record
        return true
    }

    override fun find(userId: UserId, clientOrderId: ClientOrderId): ConditionalSubmissionRecord? =
        records[clientOrderId]?.takeIf { it.userId == userId }

    override fun markAccepted(
        userId: UserId,
        clientOrderId: ClientOrderId,
        conditionalOrderId: String,
        at: Instant,
    ) =
        update(userId, clientOrderId) {
            it.copy(state = SubmissionState.ACCEPTED, conditionalOrderId = conditionalOrderId)
        }

    override fun markResolved(
        userId: UserId,
        clientOrderId: ClientOrderId,
        state: SubmissionState,
        reason: String,
        at: Instant,
    ) = update(userId, clientOrderId) { it.copy(state = state, reason = reason) }

    override fun markUnknown(
        userId: UserId,
        clientOrderId: ClientOrderId,
        reason: String,
        at: Instant,
    ) = update(userId, clientOrderId) { it.copy(state = SubmissionState.UNKNOWN, reason = reason) }

    override fun findUnresolved(userId: UserId): List<ConditionalSubmissionRecord> =
        records.values.filter { it.userId == userId && !it.state.isResolved }

    override fun claimedConditionalOrderIds(userId: UserId): Set<String> =
        records.values.filter { it.userId == userId }.mapNotNull { it.conditionalOrderId }.toSet()

    fun stateOf(clientOrderId: ClientOrderId): SubmissionState? = records[clientOrderId]?.state

    private fun update(
        userId: UserId,
        clientOrderId: ClientOrderId,
        change: (ConditionalSubmissionRecord) -> ConditionalSubmissionRecord,
    ) {
        val record = find(userId, clientOrderId) ?: error("조건주문 요청 $clientOrderId 의 기록이 없음")
        records[clientOrderId] = change(record)
    }
}
