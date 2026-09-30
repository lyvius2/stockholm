package banghak.stock.core.port

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.ConditionalSubmissionRecord
import banghak.stock.core.domain.trading.SubmissionState
import java.time.Instant

/**
 * 조건주문 요청 기록(`conditional_submission`).
 * 보내기 전에 남겨 데몬이 다시 떠도 결과 확인을 이어 감.
 * 모든 조회는 사용자 범위 안에서만 동작함.
 */
interface ConditionalSubmissionStorePort {
    /**
     * 보내는 중 상태로 새로 기록함.
     *
     * @return 새로 기록했으면 true, 같은 멱등 키가 이미 있으면 false
     */
    fun tryBegin(record: ConditionalSubmissionRecord): Boolean

    fun find(userId: UserId, clientOrderId: ClientOrderId): ConditionalSubmissionRecord?

    fun markAccepted(
        userId: UserId,
        clientOrderId: ClientOrderId,
        conditionalOrderId: String,
        at: Instant,
    )

    fun markResolved(
        userId: UserId,
        clientOrderId: ClientOrderId,
        state: SubmissionState,
        reason: String,
        at: Instant,
    )

    fun markUnknown(userId: UserId, clientOrderId: ClientOrderId, reason: String, at: Instant)

    /** 결과가 정해지지 않은 요청(보내는 중·결과 모름). */
    fun findUnresolved(userId: UserId): List<ConditionalSubmissionRecord>

    /** 이미 요청에 연결된 조건주문 번호. */
    fun claimedConditionalOrderIds(userId: UserId): Set<String>
}
