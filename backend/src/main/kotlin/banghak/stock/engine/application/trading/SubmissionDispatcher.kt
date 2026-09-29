package banghak.stock.engine.application.trading

import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.error.ConfirmationRequiredException
import banghak.stock.core.domain.error.GuardrailViolationException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.OrderRejectedException
import banghak.stock.core.domain.error.OrderResultUnknownException
import banghak.stock.core.domain.guardrail.GuardrailVerdict
import banghak.stock.core.domain.guardrail.HighValueOrder
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.OrderIntent
import banghak.stock.core.domain.trading.OrderReceipt
import banghak.stock.core.domain.trading.OrderSubmission
import banghak.stock.core.domain.trading.SubmissionRecord
import banghak.stock.core.domain.trading.SubmissionState
import banghak.stock.core.port.SubmissionStorePort
import banghak.stock.core.usecase.OrderPlacement
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 새 주문과 정정이 함께 쓰는 경로.
 * 같은 키의 기존 결과 → 판정 기록·확인 검사 → 보내기 전 기록 → 전송 → 결과 기록 순서임.
 * 가드레일 판정과 증권사 호출은 부르는 쪽이 하고(주문을 내는 클래스가 가드레일을 직접 부름), 여기서는 순서와 기록만 책임짐.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class SubmissionDispatcher(
    private val journal: OrderJournal,
    private val submissions: SubmissionStorePort,
    private val clock: Clock,
) {
    /**
     * 같은 키의 요청이 이미 있으면 그 결과.
     * 가드레일·증권사를 다시 거치지 않음.
     * 같은 키에 다른 내용이면 멱등 결과를 돌려주지 않고 거부함(토스도 같은 키 다른 본문은 422).
     */
    fun findPlacement(
        userId: UserId,
        clientOrderId: ClientOrderId,
        isSameRequest: (SubmissionRecord) -> Boolean,
    ): OrderPlacement? {
        val existing = submissions.find(userId, clientOrderId) ?: return null
        requireSameRequest(existing, isSameRequest(existing))
        return placementOf(existing)
    }

    /**
     * 가드레일을 통과하고 사람이 노트를 확인한 요청만 보낼 수 있는 형태로 만듦.
     * 판정은 통과·거부 모두 기록함.
     */
    fun approve(
        deviceId: DeviceId,
        intent: OrderIntent,
        clientOrderId: ClientOrderId,
        verdict: GuardrailVerdict,
        confirmedRules: Set<String>,
    ): OrderSubmission {
        val submission = OrderSubmission(intent, clientOrderId, isHighValueConfirmed(verdict))
        journal.recordEvaluated(deviceId, submission, verdict)
        requirePassed(verdict)
        requireConfirmed(verdict, confirmedRules)
        return submission
    }

    /**
     * 보내기 전에 요청을 남기고 [send] 로 보냄.
     * 접수 뒤 기록이 실패하면 예외가 올라가고 요청은 SENDING 으로 남아 결과 확인 대상이 됨.
     */
    fun dispatch(record: SubmissionRecord, send: () -> OrderReceipt): OrderPlacement {
        if (!journal.beginSubmission(record)) {
            val existing = existing(record)
            requireSameRequest(existing, existing.isSameRequestAs(record))
            return placementOf(existing)
        }
        return try {
            val receipt = send()
            journal.recordAccepted(record, receipt.brokerOrderId)
            OrderPlacement.Accepted(record.clientOrderId, receipt.brokerOrderId)
        } catch (e: OrderResultUnknownException) {
            journal.recordUnknown(record, e.message.orEmpty())
            OrderPlacement.Pending(record.clientOrderId)
        } catch (e: OrderRejectedException) {
            journal.recordRejected(record, SubmissionState.REJECTED, e.message.orEmpty())
            throw e
        } catch (e: BrokerUnavailableException) {
            journal.recordRejected(record, SubmissionState.NOT_SENT, e.message.orEmpty())
            throw e
        }
    }

    fun sendingRecord(
        submission: OrderSubmission,
        deviceId: DeviceId,
        replacesBrokerOrderId: String?,
    ) =
        SubmissionRecord(
            submission,
            deviceId,
            SubmissionState.SENDING,
            brokerOrderId = null,
            reason = null,
            sentAt = clock.instant(),
            replacesBrokerOrderId = replacesBrokerOrderId,
        )

    private fun placementOf(record: SubmissionRecord): OrderPlacement =
        when (record.state) {
            SubmissionState.ACCEPTED ->
                OrderPlacement.Accepted(
                    record.clientOrderId,
                    record.brokerOrderId ?: error("접수된 요청 ${record.clientOrderId} 에 주문 번호가 없음"),
                )
            SubmissionState.SENDING,
            SubmissionState.UNKNOWN -> OrderPlacement.Pending(record.clientOrderId)
            SubmissionState.NEEDS_REVIEW -> OrderPlacement.NeedsReview(record.clientOrderId)
            SubmissionState.REJECTED -> throw OrderRejectedException(record.reason.orEmpty())
            SubmissionState.NOT_SENT -> throw BrokerUnavailableException(record.reason.orEmpty())
        }

    private fun requireSameRequest(existing: SubmissionRecord, isSame: Boolean) {
        if (!isSame)
            throw InvalidValueException(
                "요청 키 ${existing.clientOrderId} 에 처음과 다른 주문 내용이 왔음. 새 요청 키로 보낼 것"
            )
    }

    private fun existing(record: SubmissionRecord): SubmissionRecord =
        submissions.find(record.submission.intent.userId, record.clientOrderId)
            ?: throw InvalidValueException("멱등 키 ${record.clientOrderId} 가 다른 사용자의 요청에 쓰였음")

    private fun requirePassed(verdict: GuardrailVerdict) {
        if (verdict is GuardrailVerdict.Rejected)
            throw GuardrailViolationException(verdict.violations.map { "${it.rule}: ${it.reason}" })
    }

    private fun requireConfirmed(verdict: GuardrailVerdict, confirmedRules: Set<String>) {
        val unconfirmed = verdict.notes.filterNot { it.rule in confirmedRules }
        if (unconfirmed.isNotEmpty())
            throw ConfirmationRequiredException(unconfirmed.map { "${it.rule}: ${it.text}" })
    }

    private fun isHighValueConfirmed(verdict: GuardrailVerdict): Boolean =
        verdict.notes.any { it.rule == HighValueOrder.NAME }
}
