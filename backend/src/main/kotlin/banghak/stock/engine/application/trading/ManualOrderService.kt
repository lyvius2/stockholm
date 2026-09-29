package banghak.stock.engine.application.trading

import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.error.ConfirmationRequiredException
import banghak.stock.core.domain.error.DomainException
import banghak.stock.core.domain.error.GuardrailViolationException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.OrderRejectedException
import banghak.stock.core.domain.error.OrderResultUnknownException
import banghak.stock.core.domain.guardrail.GuardrailVerdict
import banghak.stock.core.domain.guardrail.HighValueOrder
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.trading.BrokerOrderRecord
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.ClosedOrdersQuery
import banghak.stock.core.domain.trading.ManualTrigger
import banghak.stock.core.domain.trading.OrderIntent
import banghak.stock.core.domain.trading.OrderSubmission
import banghak.stock.core.domain.trading.RecommendationTrigger
import banghak.stock.core.domain.trading.SubmissionMatcher
import banghak.stock.core.domain.trading.SubmissionRecord
import banghak.stock.core.domain.trading.SubmissionState
import banghak.stock.core.port.SubmissionStorePort
import banghak.stock.core.port.TradingPort
import banghak.stock.core.port.UserAccountPort
import banghak.stock.core.usecase.EvaluateGuardrailUseCase
import banghak.stock.core.usecase.ManualOrderRequest
import banghak.stock.core.usecase.OrderPlacement
import banghak.stock.core.usecase.PlaceManualOrderUseCase
import banghak.stock.core.usecase.ResolvePendingOrdersUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 수동 주문을 가드레일 → 증권사 순서로 보냄.
 * 멱등 키는 화면이 주고, 같은 키의 두 번째 요청은 첫 요청의 결과를 돌려받음.
 * 결과를 모르면 증권사 주문 목록을 읽어 확인하고 절대 다시 보내지 않음.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class ManualOrderService(
    private val guardrail: EvaluateGuardrailUseCase,
    private val trading: TradingPort,
    private val journal: OrderJournal,
    private val submissions: SubmissionStorePort,
    private val users: UserAccountPort,
    private val clock: Clock,
) : PlaceManualOrderUseCase, ResolvePendingOrdersUseCase {
    override fun place(request: ManualOrderRequest): OrderPlacement {
        val intent = request.intent
        val deviceId = deviceOf(intent)
        submissions.find(intent.userId, request.clientOrderId)?.let {
            return placementOf(it)
        }
        val verdict = guardrail.evaluate(intent, request.clientOrderId)
        val submission =
            OrderSubmission(intent, request.clientOrderId, isHighValueConfirmed(verdict))
        journal.recordEvaluated(deviceId, submission, verdict)
        requirePassed(verdict)
        requireConfirmed(verdict, request.confirmedRules)
        val record =
            SubmissionRecord(
                submission,
                deviceId,
                SubmissionState.SENDING,
                null,
                null,
                clock.instant(),
            )
        if (!journal.beginSubmission(record))
            return placementOf(existing(intent.userId, record.clientOrderId))
        return send(record)
    }

    override fun resolvePending() {
        users.findAll().forEach { account ->
            submissions.findUnresolved(account.userId).forEach(::resolve)
        }
    }

    // 접수 뒤 기록이 실패하면 예외가 올라가고 요청은 SENDING 으로 남아 확인 대상이 됨
    private fun send(record: SubmissionRecord): OrderPlacement =
        try {
            val receipt = trading.placeOrder(record.submission)
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

    private fun resolve(record: SubmissionRecord) {
        val age = Duration.between(record.sentAt, clock.instant())
        if (record.state == SubmissionState.SENDING && age < IN_FLIGHT_GRACE) return
        if (age >= RESOLVE_WINDOW) {
            giveUp(record, "${RESOLVE_WINDOW.toMinutes()}분 안에 증권사 주문 목록에서 찾지 못함")
            return
        }
        val candidates =
            try {
                ordersSince(record)
            } catch (e: DomainException) {
                log.warn(
                    "주문 요청 {} 확인용 조회 실패({}). 다음에 다시 확인함",
                    record.clientOrderId,
                    e::class.simpleName,
                )
                return
            }
        val userId = record.submission.intent.userId
        when (
            val match =
                SubmissionMatcher.match(
                    record.submission,
                    record.sentAt,
                    candidates,
                    submissions.claimedBrokerOrderIds(userId),
                )
        ) {
            is SubmissionMatcher.Match.Found ->
                journal.recordAccepted(record, match.record.brokerOrderId)
            is SubmissionMatcher.Match.Ambiguous ->
                giveUp(record, "맞는 주문이 여러 건임: ${match.brokerOrderIds.joinToString()}")
            SubmissionMatcher.Match.NotFound -> Unit
        }
    }

    // 토스 주문 목록의 날짜는 KST 기준임
    private fun ordersSince(record: SubmissionRecord): List<BrokerOrderRecord> {
        val intent = record.submission.intent
        val zone = Market.KR.zone
        val query =
            ClosedOrdersQuery(
                intent.market,
                record.sentAt.atZone(zone).toLocalDate(),
                clock.instant().atZone(zone).toLocalDate(),
                cursor = null,
            )
        return trading.openOrders(intent.userId, intent.market) + closedOrders(intent.userId, query)
    }

    private fun closedOrders(userId: UserId, first: ClosedOrdersQuery): List<BrokerOrderRecord> {
        val found = mutableListOf<BrokerOrderRecord>()
        var query: ClosedOrdersQuery? = first
        repeat(MAX_CLOSED_PAGES) {
            val page = trading.closedOrders(userId, query ?: return found)
            found += page.orders
            query = page.nextCursor?.let { first.copy(cursor = it) }
        }
        return found
    }

    private fun giveUp(record: SubmissionRecord, reason: String) {
        journal.recordNeedsReview(record, "$reason. 토스에서 직접 확인할 것")
        log.warn("주문 요청 {} 의 결과를 확인하지 못함. 사람이 확인해야 함", record.clientOrderId)
    }

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

    private fun existing(userId: UserId, clientOrderId: ClientOrderId): SubmissionRecord =
        submissions.find(userId, clientOrderId)
            ?: throw InvalidValueException("멱등 키 $clientOrderId 가 다른 사용자의 요청에 쓰였음")

    // 확인 창을 거친 주문 요청은 화면이 보낸 디바이스로 기록함
    private fun deviceOf(intent: OrderIntent): DeviceId =
        when (val trigger = intent.trigger) {
            is ManualTrigger -> trigger.device
            is RecommendationTrigger ->
                throw InvalidValueException("AI 추천 승인 주문은 추천 기능(4단계)과 함께 받음")
            else -> throw InvalidValueException("수동 주문이 아님: ${trigger::class.simpleName}")
        }

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

    companion object {
        private val log = LoggerFactory.getLogger(ManualOrderService::class.java)

        // 보내는 중인 요청과 겹치지 않도록 연결 5초 + 읽기 15초 + 호출 한도 대기 3초보다 길게 기다림
        private val IN_FLIGHT_GRACE: Duration = Duration.ofSeconds(30)

        // 이 시간 안에 주문 목록에서 찾지 못하면 사람이 확인함
        private val RESOLVE_WINDOW: Duration = Duration.ofMinutes(5)

        // 종료 주문은 한 쪽에 100건이라 하루 500건까지만 뒤짐
        private const val MAX_CLOSED_PAGES = 5
    }
}
