package banghak.stock.engine.application.trading

import banghak.stock.core.domain.error.DomainException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.trading.ClosedOrdersQuery
import banghak.stock.core.domain.trading.OrderStatus
import banghak.stock.core.domain.trading.SubmissionMatcher
import banghak.stock.core.domain.trading.SubmissionRecord
import banghak.stock.core.domain.trading.SubmissionState
import banghak.stock.core.port.SubmissionStorePort
import banghak.stock.core.port.TradingPort
import banghak.stock.core.port.UserAccountPort
import banghak.stock.core.usecase.ResolvePendingOrdersUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 결과를 모르는 요청(새 주문·정정)을 증권사에서 읽어 확인함.
 * 읽기만 하고 절대 다시 보내지 않음.
 * 새 주문은 주문 목록에서 속성으로 찾고, 정정은 원주문 상태로 판정함.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class PendingSubmissionResolver(
    private val trading: TradingPort,
    private val journal: OrderJournal,
    private val submissions: SubmissionStorePort,
    private val users: UserAccountPort,
    private val clock: Clock,
) : ResolvePendingOrdersUseCase {
    override fun resolvePending() {
        users.findAll().forEach { account ->
            submissions.findUnresolved(account.userId).forEach(::resolve)
        }
    }

    private fun resolve(record: SubmissionRecord) {
        val age = Duration.between(record.sentAt, clock.instant())
        val original = record.replacesBrokerOrderId
        // 정정은 원주문 상태로 판정하므로 결과 모름이어도 토스가 처리할 시간을 줌
        if ((record.state == SubmissionState.SENDING || original != null) && age < IN_FLIGHT_GRACE)
            return
        if (age >= RESOLVE_WINDOW) {
            giveUp(record, "${RESOLVE_WINDOW.toMinutes()}분 안에 결과를 확인하지 못함")
            return
        }
        try {
            if (original != null) resolveAmendment(record, original) else resolveNewOrder(record)
        } catch (e: DomainException) {
            log.warn(
                "주문 요청 {} 확인용 조회 실패({}). 다음에 다시 확인함",
                record.clientOrderId,
                e::class.simpleName,
            )
        }
    }

    // 토스 주문 응답에는 원주문 연결 정보가 없어 원주문 상태가 유일한 증거임.
    // 원주문이 그대로 열려 있으면 정정은 닿지 않았거나 거부된 것임(거부되면 원주문이 이전 상태로 돌아옴).
    // 정정됐어도 새 주문 번호는 속성만으로 고르지 않음(같은 모양의 앱 주문과 구별할 수 없음)
    private fun resolveAmendment(record: SubmissionRecord, originalId: String) {
        val original = trading.lookupOrder(record.submission.intent.userId, originalId)
        when {
            original.status == OrderStatus.PENDING_AMEND -> Unit
            original.status.isChangeable ->
                journal.recordRejected(
                    record,
                    SubmissionState.REJECTED,
                    "정정이 반영되지 않음(원주문이 그대로 열려 있음)",
                )
            original.status == OrderStatus.REPLACED ->
                giveUp(record, "정정은 반영됐으나 토스가 새 주문 번호를 알려 주지 않음")
            else ->
                journal.recordRejected(
                    record,
                    SubmissionState.REJECTED,
                    "원주문이 이미 ${original.status} 라 정정되지 않음",
                )
        }
    }

    // 종료 목록이 상한에서 잘리면 한 건이라는 판단도, 없다는 판단도 할 수 없어 이번에는 판정하지 않음
    private fun resolveNewOrder(record: SubmissionRecord) {
        val intent = record.submission.intent
        val zone = Market.KR.zone
        val query =
            ClosedOrdersQuery(
                intent.market,
                record.sentAt.atZone(zone).toLocalDate(),
                clock.instant().atZone(zone).toLocalDate(),
                cursor = null,
            )
        val closed = trading.closedOrderPages(intent.userId, query, MAX_CLOSED_PAGES)
        if (closed.isTruncated) {
            log.warn(
                "주문 요청 {} 확인: 종료 주문이 {}쪽을 넘어 이번에는 판정하지 않음",
                record.clientOrderId,
                MAX_CLOSED_PAGES,
            )
            return
        }
        val candidates = trading.openOrders(intent.userId, intent.market) + closed.orders
        val claimed = submissions.claimedBrokerOrderIds(intent.userId)
        when (
            val match =
                SubmissionMatcher.match(record.submission, record.sentAt, candidates, claimed)
        ) {
            is SubmissionMatcher.Match.Found ->
                journal.recordAccepted(record, match.record.brokerOrderId)
            is SubmissionMatcher.Match.Ambiguous ->
                giveUp(record, "맞는 주문이 여러 건임: ${match.brokerOrderIds.joinToString()}")
            SubmissionMatcher.Match.NotFound -> Unit
        }
    }

    private fun giveUp(record: SubmissionRecord, reason: String) {
        journal.recordNeedsReview(record, "$reason. 토스에서 직접 확인할 것")
        log.warn("주문 요청 {} 의 결과를 확인하지 못함. 사람이 확인해야 함", record.clientOrderId)
    }

    companion object {
        private val log = LoggerFactory.getLogger(PendingSubmissionResolver::class.java)

        // 보내는 중인 요청과 겹치지 않도록 연결 5초 + 읽기 15초 + 호출 한도 대기 3초보다 길게 기다림
        private val IN_FLIGHT_GRACE: Duration = Duration.ofSeconds(30)

        // 이 시간 안에 주문 목록에서 찾지 못하면 사람이 확인함
        private val RESOLVE_WINDOW: Duration = Duration.ofMinutes(5)

        // 종료 주문은 한 쪽에 100건이라 하루 500건까지만 뒤짐
        private const val MAX_CLOSED_PAGES = 5
    }
}
