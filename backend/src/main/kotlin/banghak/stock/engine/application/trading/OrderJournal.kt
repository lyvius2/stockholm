package banghak.stock.engine.application.trading

import banghak.stock.core.domain.eventlog.DomainEvent
import banghak.stock.core.domain.eventlog.GuardrailEvaluated
import banghak.stock.core.domain.eventlog.OrderIntended
import banghak.stock.core.domain.eventlog.OrderRejected
import banghak.stock.core.domain.eventlog.OrderResultUnknown
import banghak.stock.core.domain.eventlog.OrderSubmitted
import banghak.stock.core.domain.guardrail.GuardrailVerdict
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.trading.BrokerOrder
import banghak.stock.core.domain.trading.OrderStatus
import banghak.stock.core.domain.trading.OrderSubmission
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.SubmissionRecord
import banghak.stock.core.domain.trading.SubmissionState
import banghak.stock.core.port.BrokerOrderStorePort
import banghak.stock.core.port.EventStore
import banghak.stock.core.port.SubmissionStorePort
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 주문의 흔적을 이벤트 로그·요청 기록·주문 기록에 남김.
 * 이벤트 추가와 기록 갱신은 같은 트랜잭션임(이벤트 로그가 곧 아웃박스).
 * 증권사 호출은 트랜잭션 밖에서 하고 여기서는 결과만 기록함.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class OrderJournal(
    private val events: EventStore,
    private val submissions: SubmissionStorePort,
    private val orders: BrokerOrderStorePort,
    private val clock: Clock,
) {
    @Transactional
    fun recordEvaluated(
        deviceId: DeviceId,
        submission: OrderSubmission,
        verdict: GuardrailVerdict,
    ) {
        val violations =
            (verdict as? GuardrailVerdict.Rejected)?.violations.orEmpty().map {
                "${it.rule}: ${it.reason}"
            }
        append(
            deviceId,
            submission,
            GuardrailEvaluated(submission.clientOrderId, verdict.isPassed, violations),
        )
    }

    /**
     * 보내기 직전에 요청과 의도를 남김.
     *
     * @return 새로 남겼으면 true, 같은 멱등 키의 요청이 이미 있으면 false(보내지 말 것)
     */
    @Transactional
    fun beginSubmission(record: SubmissionRecord): Boolean {
        if (!submissions.tryBegin(record)) return false
        val intent = record.submission.intent
        append(
            record.deviceId,
            record.submission,
            OrderIntended(
                record.clientOrderId,
                intent.symbol,
                intent.side,
                intent.kind,
                intent.quantity,
                intent.limitPrice,
                intent.orderAmount,
                intent.origin,
            ),
        )
        return true
    }

    // 접수 응답에는 상태가 없음.
    // 막 접수된 주문은 체결 대기이며 이후 상태는 실시간 주문 채널이 바꿈
    @Transactional
    fun recordAccepted(record: SubmissionRecord, brokerOrderId: String) {
        val submission = record.submission
        val now = clock.instant()
        submissions.markAccepted(submission.intent.userId, record.clientOrderId, brokerOrderId, now)
        orders.recordAccepted(
            BrokerOrder(
                clientOrderId = record.clientOrderId,
                brokerOrderId = brokerOrderId,
                replacesBrokerOrderId = null,
                intent = submission.intent,
                status = OrderStatus.PENDING,
                filledQuantity = Quantity.ZERO,
                averageFilledPrice = null,
                updatedAt = now,
            ),
            submission.isHighValueConfirmed,
        )
        append(record.deviceId, submission, OrderSubmitted(record.clientOrderId, brokerOrderId))
    }

    @Transactional
    fun recordUnknown(record: SubmissionRecord, reason: String) {
        submissions.markUnknown(
            record.submission.intent.userId,
            record.clientOrderId,
            reason,
            clock.instant(),
        )
        append(record.deviceId, record.submission, OrderResultUnknown(record.clientOrderId, reason))
    }

    /** 증권사가 받지 않았거나([SubmissionState.REJECTED]) 닿기 전에 실패함([SubmissionState.NOT_SENT]). */
    @Transactional
    fun recordRejected(record: SubmissionRecord, state: SubmissionState, reason: String) {
        submissions.markResolved(
            record.submission.intent.userId,
            record.clientOrderId,
            state,
            reason,
            clock.instant(),
        )
        append(record.deviceId, record.submission, OrderRejected(record.clientOrderId, reason))
    }

    @Transactional
    fun recordNeedsReview(record: SubmissionRecord, reason: String) {
        submissions.markResolved(
            record.submission.intent.userId,
            record.clientOrderId,
            SubmissionState.NEEDS_REVIEW,
            reason,
            clock.instant(),
        )
        append(record.deviceId, record.submission, OrderResultUnknown(record.clientOrderId, reason))
    }

    private fun append(deviceId: DeviceId, submission: OrderSubmission, event: DomainEvent) {
        events.append(submission.intent.userId, deviceId, listOf(event), clock.instant())
    }
}
