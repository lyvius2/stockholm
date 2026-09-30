package banghak.stock.engine.application.trading

import banghak.stock.core.domain.eventlog.ConditionalOrderRegistered
import banghak.stock.core.domain.eventlog.ConditionalOrderRequested
import banghak.stock.core.domain.eventlog.DomainEvent
import banghak.stock.core.domain.eventlog.GuardrailEvaluated
import banghak.stock.core.domain.eventlog.OrderRejected
import banghak.stock.core.domain.eventlog.OrderResultUnknown
import banghak.stock.core.domain.guardrail.GuardrailVerdict
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.ConditionalOrderSubmission
import banghak.stock.core.domain.trading.ConditionalSubmissionRecord
import banghak.stock.core.domain.trading.SubmissionState
import banghak.stock.core.port.ConditionalSubmissionStorePort
import banghak.stock.core.port.EventStore
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 조건주문의 흔적을 이벤트 로그·요청 기록에 남김.
 * 이벤트 추가와 기록 갱신은 같은 트랜잭션임.
 * 증권사 호출은 트랜잭션 밖에서 하고 여기서는 결과만 기록함.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class ConditionalOrderJournal(
    private val events: EventStore,
    private val submissions: ConditionalSubmissionStorePort,
    private val clock: Clock,
) {
    @Transactional
    fun recordEvaluated(submission: ConditionalOrderSubmission, verdict: GuardrailVerdict) {
        val violations =
            (verdict as? GuardrailVerdict.Rejected)?.violations.orEmpty().map {
                "${it.rule}: ${it.reason}"
            }
        append(
            submission,
            GuardrailEvaluated(submission.clientOrderId, verdict.isPassed, violations),
        )
    }

    /**
     * 보내기 직전에 요청을 남김.
     *
     * @return 새로 남겼으면 true, 같은 멱등 키의 요청이 이미 있으면 false(보내지 말 것)
     */
    @Transactional
    fun beginSubmission(record: ConditionalSubmissionRecord): Boolean {
        if (!submissions.tryBegin(record)) return false
        val intent = record.submission.intent
        append(
            record.submission,
            ConditionalOrderRequested(
                record.clientOrderId,
                intent.symbol,
                intent.type,
                intent.quantity,
                intent.first,
                intent.second,
                intent.expireDate,
                record.replacesConditionalOrderId,
            ),
        )
        return true
    }

    @Transactional
    fun recordAccepted(record: ConditionalSubmissionRecord, conditionalOrderId: String) {
        submissions.markAccepted(
            record.userId,
            record.clientOrderId,
            conditionalOrderId,
            clock.instant(),
        )
        append(
            record.submission,
            ConditionalOrderRegistered(record.clientOrderId, conditionalOrderId),
        )
    }

    @Transactional
    fun recordUnknown(record: ConditionalSubmissionRecord, reason: String) {
        submissions.markUnknown(record.userId, record.clientOrderId, reason, clock.instant())
        append(record.submission, OrderResultUnknown(record.clientOrderId, reason))
    }

    /** 증권사가 받지 않았거나([SubmissionState.REJECTED]) 닿기 전에 실패함([SubmissionState.NOT_SENT]). */
    @Transactional
    fun recordRejected(
        record: ConditionalSubmissionRecord,
        state: SubmissionState,
        reason: String,
    ) {
        submissions.markResolved(
            record.userId,
            record.clientOrderId,
            state,
            reason,
            clock.instant(),
        )
        append(record.submission, OrderRejected(record.clientOrderId, reason))
    }

    @Transactional
    fun recordNeedsReview(record: ConditionalSubmissionRecord, reason: String) {
        submissions.markResolved(
            record.userId,
            record.clientOrderId,
            SubmissionState.NEEDS_REVIEW,
            reason,
            clock.instant(),
        )
        append(record.submission, OrderResultUnknown(record.clientOrderId, reason))
    }

    /** 요청 기록이 없는 조작(취소)의 흔적을 남김. */
    @Transactional
    fun recordEvent(userId: UserId, deviceId: DeviceId, event: DomainEvent) {
        events.append(userId, deviceId, listOf(event), clock.instant())
    }

    private fun append(submission: ConditionalOrderSubmission, event: DomainEvent) {
        val intent = submission.intent
        events.append(intent.userId, intent.requestedBy, listOf(event), clock.instant())
    }
}
