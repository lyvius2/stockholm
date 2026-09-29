package banghak.stock.core.domain.trading

import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.money.Money
import java.time.Duration
import java.time.Instant

/** 증권사에 보낸 주문 요청의 진행 상태. */
enum class SubmissionState(val isResolved: Boolean) {
    /**
     * 보내기 직전에 기록함.
     * 이 상태로 남아 있으면 결과를 모르는 것과 같음.
     */
    SENDING(isResolved = false),
    ACCEPTED(isResolved = true),
    /** 보냈지만 결과를 모름(타임아웃). */
    UNKNOWN(isResolved = false),
    REJECTED(isResolved = true),
    /** 증권사에 닿기 전에 실패함(연결 전 실패·호출 한도). */
    NOT_SENT(isResolved = true),
    /**
     * 정해진 시간 안에 확인하지 못함.
     * 사람이 토스에서 확인해야 함.
     */
    NEEDS_REVIEW(isResolved = true),
}

/**
 * 주문 요청 기록.
 * 멱등 키마다 하나이며 보내기 전에 만들어 데몬이 다시 떠도 결과 확인을 이어 감.
 */
data class SubmissionRecord(
    val submission: OrderSubmission,
    val deviceId: DeviceId,
    val state: SubmissionState,
    val brokerOrderId: String?,
    val reason: String?,
    val sentAt: Instant,
) {
    val clientOrderId: ClientOrderId
        get() = submission.clientOrderId
}

/**
 * 결과를 모르는 요청을 증권사 주문 목록에서 찾음.
 * 토스 주문 조회에는 멱등 키가 없어 속성으로 맞춤.
 * 같은 키로 다시 보내는 것은 조회가 아님(첫 요청이 닿지 않았으면 새 주문이 생김).
 */
object SubmissionMatcher {
    // 로컬 시계와 토스 접수 시각의 차이
    private val CLOCK_SKEW: Duration = Duration.ofSeconds(10)

    sealed interface Match {
        data class Found(val record: BrokerOrderRecord) : Match

        data class Ambiguous(val brokerOrderIds: List<String>) : Match

        data object NotFound : Match
    }

    fun match(
        submission: OrderSubmission,
        sentAt: Instant,
        candidates: List<BrokerOrderRecord>,
        claimed: Set<String>,
    ): Match {
        val earliest = sentAt.minus(CLOCK_SKEW)
        val matching = candidates.filter {
            it.brokerOrderId !in claimed &&
                !it.orderedAt.isBefore(earliest) &&
                isSameOrder(submission.intent, it)
        }
        return when (matching.size) {
            0 -> Match.NotFound
            1 -> Match.Found(matching.single())
            else -> Match.Ambiguous(matching.map { it.brokerOrderId })
        }
    }

    // 금액 주문은 유효 조건을 보내지 않으므로 비교하지 않음
    private fun isSameOrder(intent: OrderIntent, record: BrokerOrderRecord): Boolean =
        record.symbol == intent.symbol &&
            record.side == intent.side &&
            record.kind == intent.kind &&
            (intent.orderAmount != null || record.timeInForce == intent.timeInForce) &&
            isSameMoney(record.limitPrice, intent.limitPrice) &&
            isSameMoney(record.orderAmount, intent.orderAmount) &&
            isSameQuantity(record.quantity, intent.quantity)

    private fun isSameMoney(left: Money?, right: Money?): Boolean =
        if (left == null || right == null) left == right
        else left.currency == right.currency && left.amount.compareTo(right.amount) == 0

    private fun isSameQuantity(left: Quantity?, right: Quantity?): Boolean =
        if (left == null || right == null) left == right else left.value.compareTo(right.value) == 0
}
