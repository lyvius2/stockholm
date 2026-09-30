package banghak.stock.engine.application.trading

import banghak.stock.core.domain.error.DomainException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.ConditionalOrderRecord
import banghak.stock.core.domain.trading.ConditionalOrderScope
import banghak.stock.core.domain.trading.ConditionalOrdersQuery
import banghak.stock.core.domain.trading.ConditionalSubmissionMatcher
import banghak.stock.core.domain.trading.ConditionalSubmissionRecord
import banghak.stock.core.domain.trading.SubmissionState
import banghak.stock.core.port.ConditionalOrderPort
import banghak.stock.core.port.ConditionalSubmissionStorePort
import banghak.stock.core.port.UserAccountPort
import banghak.stock.core.usecase.ResolvePendingConditionalOrdersUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 결과를 모르는 조건주문 등록·수정을 토스에서 읽어 확인함.
 * 읽기만 하고 절대 다시 보내지 않음.
 * 등록은 그 종목의 조건주문 목록에서 속성으로 찾고, 수정은 기존 조건주문이 남아 있는지로 판정함.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class PendingConditionalOrderResolver(
    private val conditionalOrders: ConditionalOrderPort,
    private val journal: ConditionalOrderJournal,
    private val submissions: ConditionalSubmissionStorePort,
    private val users: UserAccountPort,
    private val clock: Clock,
) : ResolvePendingConditionalOrdersUseCase {
    override fun resolvePending() {
        users.findAll().forEach { account ->
            submissions.findUnresolved(account.userId).forEach(::resolve)
        }
    }

    private fun resolve(record: ConditionalSubmissionRecord) {
        val age = Duration.between(record.sentAt, clock.instant())
        val original = record.replacesConditionalOrderId
        // 보내는 중인 요청과 겹치지 않고 토스가 처리할 시간을 주도록 상태와 무관하게 기다림.
        // 정확히 30초부터 확인함
        if (age < IN_FLIGHT_GRACE) return
        if (age >= RESOLVE_WINDOW) {
            giveUp(record, "${RESOLVE_WINDOW.toMinutes()}분 안에 결과를 확인하지 못함")
            return
        }
        try {
            if (original != null) resolveAmendment(record, original)
            else resolveRegistration(record)
        } catch (e: DomainException) {
            log.warn("조건주문 요청 확인용 조회 실패({}). 다음에 다시 확인함", e::class.simpleName)
        }
    }

    // 토스 수정은 기존 것을 취소하고 새로 만듦.
    // 기존 조건주문이 그대로 열려 있으면 수정은 닿지 않았거나 거부된 것임.
    // 사라졌으면 반영됐을 수 있으나 새 번호는 속성만으로 고르지 않음(앱에서 만든 같은 모양과 구별할 수 없음)
    private fun resolveAmendment(record: ConditionalSubmissionRecord, originalId: String) {
        val original =
            try {
                conditionalOrders.lookupConditionalOrder(record.userId, originalId)
            } catch (e: InvalidValueException) {
                null
            }
        if (original != null && original.status.isOpen)
            journal.recordRejected(
                record,
                SubmissionState.REJECTED,
                "수정이 반영되지 않음(기존 조건주문이 그대로 열려 있음)",
            )
        else giveUp(record, "수정은 반영됐을 수 있으나 토스가 새 조건주문 번호를 알려 주지 않음")
    }

    // 목록이 상한에서 잘리면 한 건이라는 판단도, 없다는 판단도 할 수 없어 이번에는 판정하지 않음
    private fun resolveRegistration(record: ConditionalSubmissionRecord) {
        val symbol = record.submission.intent.symbol
        val open = pagesOf(record.userId, ConditionalOrderScope.OPEN, symbol)
        val closed = pagesOf(record.userId, ConditionalOrderScope.CLOSED, symbol)
        if (open.isTruncated || closed.isTruncated) {
            log.warn("조건주문 요청 확인: 목록이 {}쪽을 넘어 이번에는 판정하지 않음", MAX_PAGES)
            return
        }
        when (
            val match =
                ConditionalSubmissionMatcher.match(
                    record.submission,
                    record.sentAt,
                    open.records + closed.records,
                    submissions.claimedConditionalOrderIds(record.userId),
                )
        ) {
            is ConditionalSubmissionMatcher.Match.Found ->
                journal.recordAccepted(record, match.record.conditionalOrderId)
            is ConditionalSubmissionMatcher.Match.Ambiguous ->
                giveUp(record, "맞는 조건주문이 여러 건임: ${match.conditionalOrderIds.joinToString()}")
            is ConditionalSubmissionMatcher.Match.DirectionUnverifiable ->
                giveUp(
                    record,
                    "같은 모양의 조건주문이 있으나 토스 조회에 매매 방향이 없어 확정하지 못함: " +
                        match.conditionalOrderIds.joinToString(),
                )
            ConditionalSubmissionMatcher.Match.NotFound -> Unit
        }
    }

    private fun pagesOf(userId: UserId, scope: ConditionalOrderScope, symbol: Symbol): Pages {
        val records = mutableListOf<ConditionalOrderRecord>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val page =
                conditionalOrders.conditionalOrders(
                    userId,
                    ConditionalOrdersQuery(scope, symbol, cursor),
                )
            records += page.conditionalOrders
            cursor = page.nextCursor ?: return Pages(records, isTruncated = false)
        }
        return Pages(records, isTruncated = true)
    }

    private fun giveUp(record: ConditionalSubmissionRecord, reason: String) {
        journal.recordNeedsReview(record, "$reason. 토스 조건주문 목록에서 직접 확인할 것")
        log.warn("조건주문 요청 하나의 결과를 확인하지 못함. 사람이 확인해야 함")
    }

    private data class Pages(val records: List<ConditionalOrderRecord>, val isTruncated: Boolean)

    companion object {
        private val log = LoggerFactory.getLogger(PendingConditionalOrderResolver::class.java)

        // 보내는 중인 요청과 겹치지 않도록 연결 5초 + 읽기 15초 + 호출 한도 대기 3초보다 길게 기다림
        private val IN_FLIGHT_GRACE: Duration = Duration.ofSeconds(30)

        // 이 시간 안에 목록에서 찾지 못하면 사람이 확인함
        private val RESOLVE_WINDOW: Duration = Duration.ofMinutes(5)

        // 한 종목의 조건주문은 한 쪽에 100건이라 500건까지만 뒤짐
        private const val MAX_PAGES = 5
    }
}
