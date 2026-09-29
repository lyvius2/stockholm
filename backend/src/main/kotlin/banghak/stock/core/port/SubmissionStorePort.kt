package banghak.stock.core.port

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.SubmissionRecord
import banghak.stock.core.domain.trading.SubmissionState
import java.time.Instant

/**
 * 주문 요청 기록(`order_submission`).
 * 보내기 전에 남겨 데몬이 다시 떠도 결과 확인을 이어 감.
 * 모든 조회는 사용자 범위 안에서만 동작함.
 */
interface SubmissionStorePort {
    /**
     * 보내는 중 상태로 새로 기록함.
     *
     * @return 새로 기록했으면 true, 같은 멱등 키가 이미 있으면 false
     */
    fun tryBegin(record: SubmissionRecord): Boolean

    fun find(userId: UserId, clientOrderId: ClientOrderId): SubmissionRecord?

    fun markAccepted(
        userId: UserId,
        clientOrderId: ClientOrderId,
        brokerOrderId: String,
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
    fun findUnresolved(userId: UserId): List<SubmissionRecord>

    /** 이미 요청에 연결된 증권사 주문 번호. */
    fun claimedBrokerOrderIds(userId: UserId): Set<String>
}
