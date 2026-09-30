package banghak.stock.engine.application.trading

import banghak.stock.core.domain.error.BrokerAccessDeniedException
import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.OrderRejectedException
import banghak.stock.core.domain.error.OrderResultUnknownException
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.ConditionalOrderIntent
import banghak.stock.core.domain.trading.ConditionalOrderRecord
import banghak.stock.core.domain.trading.ConditionalOrderSubmission
import banghak.stock.core.domain.trading.ConditionalSubmissionRecord
import banghak.stock.core.domain.trading.SubmissionState
import banghak.stock.core.port.ConditionalOrderPort
import banghak.stock.core.port.ConditionalSubmissionStorePort
import banghak.stock.core.usecase.AmendConditionalOrderRequest
import banghak.stock.core.usecase.AmendConditionalOrderUseCase
import banghak.stock.core.usecase.ConditionalPlacement
import banghak.stock.core.usecase.EvaluateGuardrailUseCase
import banghak.stock.core.usecase.RegisterConditionalOrderRequest
import banghak.stock.core.usecase.RegisterConditionalOrderUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 조건주문 등록·수정을 가드레일 → 증권사 순서로 보냄.
 * 발동으로 나가는 주문은 토스 서버가 내므로 등록·수정 순간이 가드레일의 관문임.
 * 같은 키의 두 번째 요청은 첫 요청의 결과를 돌려받고, 결과를 모르면 목록을 읽어 확인하며 다시 보내지 않음.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class ConditionalOrderService(
    private val guardrail: EvaluateGuardrailUseCase,
    private val conditionalOrders: ConditionalOrderPort,
    private val journal: ConditionalOrderJournal,
    private val submissions: ConditionalSubmissionStorePort,
    private val clock: Clock,
) : RegisterConditionalOrderUseCase, AmendConditionalOrderUseCase {
    override fun register(request: RegisterConditionalOrderRequest): ConditionalPlacement {
        val intent = request.intent
        findPlacement(intent, request.clientOrderId, replaces = null)?.let {
            return it
        }
        val submission = approve(intent, request.clientOrderId, request.confirmedRules)
        return dispatch(sendingRecord(submission, replaces = null)) {
            conditionalOrders.placeConditionalOrder(submission)
        }
    }

    override fun amend(request: AmendConditionalOrderRequest): ConditionalPlacement {
        val intent = request.intent
        val target = request.conditionalOrderId
        findPlacement(intent, request.clientOrderId, target)?.let {
            return it
        }
        requireAmendable(conditionalOrders.lookupConditionalOrder(intent.userId, target), intent)
        val submission = approve(intent, request.clientOrderId, request.confirmedRules)
        return dispatch(sendingRecord(submission, target)) {
            conditionalOrders.placeConditionalAmendment(target, submission)
        }
    }

    private fun findPlacement(
        intent: ConditionalOrderIntent,
        clientOrderId: ClientOrderId,
        replaces: String?,
    ): ConditionalPlacement? {
        val existing = submissions.find(intent.userId, clientOrderId) ?: return null
        requireSameRequest(existing, existing.isSameRequestAs(intent, replaces))
        return placementOf(existing)
    }

    // 수정은 종목을 바꿀 수 없음(토스 수정 본문에 종목이 없음)
    private fun requireAmendable(target: ConditionalOrderRecord, intent: ConditionalOrderIntent) {
        if (!target.status.isOpen)
            throw InvalidValueException("이미 끝난 조건주문은 수정할 수 없음: ${target.status}")
        if (target.symbol != intent.symbol) throw InvalidValueException("조건주문 수정으로 종목을 바꿀 수 없음")
    }

    private fun approve(
        intent: ConditionalOrderIntent,
        clientOrderId: ClientOrderId,
        confirmedRules: Set<String>,
    ): ConditionalOrderSubmission {
        val verdict = guardrail.evaluateConditional(intent)
        val submission =
            ConditionalOrderSubmission(
                intent,
                clientOrderId,
                GuardrailApproval.isHighValueConfirmed(verdict),
            )
        journal.recordEvaluated(submission, verdict)
        GuardrailApproval.requireApproved(verdict, confirmedRules)
        return submission
    }

    // 접수 뒤 기록이 실패하면 예외가 올라가고 요청은 SENDING 으로 남아 결과 확인 대상이 됨
    private fun dispatch(
        record: ConditionalSubmissionRecord,
        send: () -> String,
    ): ConditionalPlacement {
        if (!journal.beginSubmission(record)) {
            val existing =
                submissions.find(record.userId, record.clientOrderId)
                    ?: throw InvalidValueException("멱등 키 ${record.clientOrderId} 가 다른 사용자의 요청에 쓰였음")
            requireSameRequest(
                existing,
                existing.isSameRequestAs(
                    record.submission.intent,
                    record.replacesConditionalOrderId,
                ),
            )
            return placementOf(existing)
        }
        return try {
            val conditionalOrderId = send()
            journal.recordAccepted(record, conditionalOrderId)
            ConditionalPlacement.Registered(record.clientOrderId, conditionalOrderId)
        } catch (e: OrderResultUnknownException) {
            journal.recordUnknown(record, e.message.orEmpty())
            ConditionalPlacement.Pending(record.clientOrderId)
        } catch (e: OrderRejectedException) {
            journal.recordRejected(record, SubmissionState.REJECTED, e.message.orEmpty())
            throw e
        } catch (e: BrokerAccessDeniedException) {
            journal.recordRejected(record, SubmissionState.REJECTED, e.message.orEmpty())
            throw e
        } catch (e: BrokerUnavailableException) {
            journal.recordRejected(record, SubmissionState.NOT_SENT, e.message.orEmpty())
            throw e
        }
    }

    private fun sendingRecord(submission: ConditionalOrderSubmission, replaces: String?) =
        ConditionalSubmissionRecord(
            submission = submission,
            state = SubmissionState.SENDING,
            conditionalOrderId = null,
            reason = null,
            sentAt = clock.instant(),
            replacesConditionalOrderId = replaces,
        )

    private fun placementOf(record: ConditionalSubmissionRecord): ConditionalPlacement =
        when (record.state) {
            SubmissionState.ACCEPTED ->
                ConditionalPlacement.Registered(
                    record.clientOrderId,
                    record.conditionalOrderId
                        ?: error("등록된 요청 ${record.clientOrderId} 에 조건주문 번호가 없음"),
                )
            SubmissionState.SENDING,
            SubmissionState.UNKNOWN -> ConditionalPlacement.Pending(record.clientOrderId)
            SubmissionState.NEEDS_REVIEW -> ConditionalPlacement.NeedsReview(record.clientOrderId)
            SubmissionState.REJECTED -> throw OrderRejectedException(record.reason.orEmpty())
            SubmissionState.NOT_SENT -> throw BrokerUnavailableException(record.reason.orEmpty())
        }

    private fun requireSameRequest(existing: ConditionalSubmissionRecord, isSame: Boolean) {
        if (!isSame)
            throw InvalidValueException(
                "요청 키 ${existing.clientOrderId} 에 처음과 다른 조건주문 내용이 왔음. 새 요청 키로 보낼 것"
            )
    }
}
