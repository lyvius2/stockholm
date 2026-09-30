package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * 조건주문의 조건 개수와 관계.
 * [SINGLE] 은 조건 하나, [OCO] 는 둘 중 먼저 닿은 쪽만, [OTO] 는 첫 조건 체결 뒤 둘째 조건 감시임.
 */
enum class ConditionalOrderType {
    SINGLE,
    OCO,
    OTO,
}

/**
 * 감시 조건 하나.
 * 현재가가 [triggerPrice] 에 닿으면 토스 서버가 [orderPrice] 로 지정가 주문을 냄.
 */
data class ConditionLeg(val side: OrderSide, val triggerPrice: Money, val orderPrice: Money) {
    init {
        if (!triggerPrice.isPositive || !orderPrice.isPositive)
            throw InvalidValueException("감시가·주문 가격은 0보다 커야 함: $triggerPrice, $orderPrice")
        if (triggerPrice.currency != orderPrice.currency)
            throw InvalidValueException("감시가와 주문 가격의 통화가 다름")
    }
}

/**
 * 사람이 등록하려는 조건주문.
 * 감시와 발동은 토스 서버가 하므로 Stockholm 의 가드레일은 등록 순간에만 거침.
 * 지정가만 받음(시장가는 미국 소수점 매도·금액 매수 두 경우에만 허용하는 원칙을 따름).
 * 생성 시 토스 규격의 형태 불변식을 검사함.
 */
data class ConditionalOrderIntent(
    val userId: UserId,
    val symbol: Symbol,
    val type: ConditionalOrderType,
    val quantity: Quantity,
    val first: ConditionLeg,
    val second: ConditionLeg?,
    val expireDate: LocalDate,
    val requestedBy: DeviceId,
    val intendedAt: Instant,
) {
    init {
        if (quantity.isZero || !quantity.isWholeShares)
            throw InvalidValueException("조건주문 수량은 1주 이상 정수 주수여야 함: $quantity")
        legs.forEach(::requireMarketCurrency)
        when (type) {
            ConditionalOrderType.SINGLE -> requireSingleShape()
            ConditionalOrderType.OCO -> requireOcoShape()
            ConditionalOrderType.OTO -> requireOtoShape()
        }
    }

    val market: Market
        get() = symbol.market

    val legs: List<ConditionLeg>
        get() = listOfNotNull(first, second)

    /**
     * 조건마다 주문 가격 × 수량 중 가장 큰 금액.
     * 고액 판정에 씀.
     */
    fun largestNotional(): Money = legs.map { it.orderPrice.times(quantity) }.maxBy { it.amount }

    private fun requireSingleShape() {
        if (second != null) throw InvalidValueException("SINGLE 조건주문에는 둘째 조건을 쓰지 않음")
    }

    // 토스 규격: OCO 는 둘 다 매도이고 첫 감시가 > 현재가 > 둘째 감시가(익절 위, 손절 아래)
    private fun requireOcoShape() {
        val stopLoss = second ?: throw InvalidValueException("OCO 조건주문에는 둘째 조건이 필요함")
        if (first.side != OrderSide.SELL || stopLoss.side != OrderSide.SELL)
            throw InvalidValueException("OCO 조건주문은 두 조건 모두 매도임")
        if (first.triggerPrice.amount <= stopLoss.triggerPrice.amount)
            throw InvalidValueException("OCO 의 첫 감시가는 둘째 감시가보다 높아야 함")
    }

    // 토스 규격: OTO 는 첫 조건이 매수, 둘째 조건이 매도임
    private fun requireOtoShape() {
        val exit = second ?: throw InvalidValueException("OTO 조건주문에는 둘째 조건이 필요함")
        if (first.side != OrderSide.BUY || exit.side != OrderSide.SELL)
            throw InvalidValueException("OTO 조건주문은 첫 조건이 매수, 둘째 조건이 매도임")
    }

    private fun requireMarketCurrency(leg: ConditionLeg) {
        if (leg.triggerPrice.currency != market.currency)
            throw InvalidValueException(
                "$market 조건주문 가격은 ${market.currency} 여야 함: ${leg.triggerPrice.currency}"
            )
    }
}

/**
 * 가드레일을 통과해 보낼 수 있는 조건주문.
 * [clientOrderId] 는 등록 요청의 토스 멱등 키임(수정 요청에는 토스 멱등 키가 없어 로컬 키로만 씀).
 */
data class ConditionalOrderSubmission(
    val intent: ConditionalOrderIntent,
    val clientOrderId: ClientOrderId,
    val isHighValueConfirmed: Boolean,
)

/** 토스 조건주문(그룹) 상태. */
enum class ConditionalOrderStatus(val isOpen: Boolean) {
    WATCHING(isOpen = true),
    PAUSED(isOpen = true),
    /** 조건 충족, 주문 생성 중. */
    ORDERING(isOpen = true),
    /**
     * 주문이 생성됨.
     * OTO 는 둘째 조건 감시가 이어짐.
     */
    ORDERED(isOpen = true),
    COMPLETED(isOpen = false),
    EXPIRED(isOpen = false),
}

/**
 * 감시 조건 하나의 상태.
 * 그룹 상태에 없는 [HOLDING]·[CANCELED] 가 있음.
 */
enum class ConditionLegStatus {
    WATCHING,
    /** OTO 둘째 조건이 첫 조건 체결을 기다림. */
    HOLDING,
    PAUSED,
    ORDERING,
    ORDERED,
    COMPLETED,
    EXPIRED,
    /** OCO 에서 반대편이 먼저 닿아 자동 취소됨. */
    CANCELED,
}

/**
 * 토스가 알려 준 감시 조건 하나.
 * 토스 응답에는 매매 방향이 없음.
 * [triggerPrice] 는 가격 조건이 아니면(목표 수익률 등, 앱에서 등록) 없음.
 * [triggeredOrderId] 는 발동으로 생긴 일반 주문 번호임.
 */
data class ConditionLegRecord(
    val isPriceTrigger: Boolean,
    val status: ConditionLegStatus,
    val triggerPrice: Money?,
    val orderPrice: Money?,
    val triggeredOrderId: String?,
)

/**
 * 토스가 알려 준 조건주문.
 * 다른 채널(토스 앱)에서 등록한 조건주문도 함께 옴.
 */
data class ConditionalOrderRecord(
    val conditionalOrderId: String,
    val type: ConditionalOrderType,
    val status: ConditionalOrderStatus,
    val symbol: Symbol,
    val quantity: Quantity,
    val kind: OrderKind,
    val expireDate: LocalDate?,
    val first: ConditionLegRecord,
    val second: ConditionLegRecord?,
    val createdAt: Instant,
)

/**
 * 조건주문 목록의 한 쪽.
 * [nextCursor] 가 없으면 마지막 쪽임.
 */
data class ConditionalOrdersPage(
    val conditionalOrders: List<ConditionalOrderRecord>,
    val nextCursor: String?,
)

/**
 * 조건주문 요청 기록.
 * 멱등 키마다 하나이며 보내기 전에 만들어 데몬이 다시 떠도 결과 확인을 이어 감.
 * 수정 요청이면 [replacesConditionalOrderId] 가 원래 조건주문이고, 토스가 새 번호를 발급함.
 */
data class ConditionalSubmissionRecord(
    val submission: ConditionalOrderSubmission,
    val state: SubmissionState,
    val conditionalOrderId: String?,
    val reason: String?,
    val sentAt: Instant,
    val replacesConditionalOrderId: String?,
) {
    val clientOrderId: ClientOrderId
        get() = submission.clientOrderId

    val userId: UserId
        get() = submission.intent.userId

    /**
     * 같은 키로 다시 온 요청이 처음과 같은 내용인지.
     * 다르면 멱등 결과를 돌려주지 않고 거부할 것.
     */
    fun isSameRequestAs(intent: ConditionalOrderIntent, replaces: String?): Boolean {
        val stored = submission.intent
        return replacesConditionalOrderId == replaces &&
            stored.userId == intent.userId &&
            stored.symbol == intent.symbol &&
            stored.type == intent.type &&
            stored.expireDate == intent.expireDate &&
            stored.quantity.value.compareTo(intent.quantity.value) == 0 &&
            stored.legs.size == intent.legs.size &&
            stored.legs.zip(intent.legs).all { (left, right) -> isSameLeg(left, right) }
    }
}

/**
 * 결과를 모르는 조건주문 등록을 토스 목록에서 찾음.
 * 토스 조건주문 조회에는 멱등 키가 없어 속성으로 맞춤.
 * 응답에 매매 방향이 없어 SINGLE 은 같은 모양이어도 우리 요청이라고 확정하지 않음.
 * 같은 가격·수량의 반대 방향 SINGLE 을 토스 앱에서 등록했을 수 있음.
 * OCO·OTO 는 형태가 방향을 정하므로 속성으로 확정함.
 */
object ConditionalSubmissionMatcher {
    // 로컬 시계와 토스 등록 시각의 차이
    private val CLOCK_SKEW: Duration = Duration.ofSeconds(10)

    sealed interface Match {
        data class Found(val record: ConditionalOrderRecord) : Match

        data class Ambiguous(val conditionalOrderIds: List<String>) : Match

        /** 같은 모양이 있으나 매매 방향을 확인할 수 없어 사람이 확인해야 함. */
        data class DirectionUnverifiable(val conditionalOrderIds: List<String>) : Match

        data object NotFound : Match
    }

    fun match(
        submission: ConditionalOrderSubmission,
        sentAt: Instant,
        candidates: List<ConditionalOrderRecord>,
        claimed: Set<String>,
    ): Match {
        val earliest = sentAt.minus(CLOCK_SKEW)
        val matching =
            candidates
                .filter {
                    it.conditionalOrderId !in claimed &&
                        !it.createdAt.isBefore(earliest) &&
                        isSameConditionalOrder(submission.intent, it)
                }
                .distinctBy { it.conditionalOrderId }
        val ids = matching.map { it.conditionalOrderId }
        return when {
            matching.isEmpty() -> Match.NotFound
            submission.intent.type == ConditionalOrderType.SINGLE ->
                Match.DirectionUnverifiable(ids)
            matching.size == 1 -> Match.Found(matching.single())
            else -> Match.Ambiguous(ids)
        }
    }

    private fun isSameConditionalOrder(
        intent: ConditionalOrderIntent,
        record: ConditionalOrderRecord,
    ): Boolean =
        record.symbol == intent.symbol &&
            record.type == intent.type &&
            record.kind == OrderKind.LIMIT &&
            record.expireDate == intent.expireDate &&
            record.quantity.value.compareTo(intent.quantity.value) == 0 &&
            isSameLegRecord(intent.first, record.first) &&
            isSameSecondLeg(intent.second, record.second)

    private fun isSameSecondLeg(leg: ConditionLeg?, record: ConditionLegRecord?): Boolean =
        if (leg == null || record == null) leg == null && record == null
        else isSameLegRecord(leg, record)

    private fun isSameLegRecord(leg: ConditionLeg, record: ConditionLegRecord): Boolean =
        record.isPriceTrigger &&
            isSameAmount(leg.triggerPrice, record.triggerPrice) &&
            isSameAmount(leg.orderPrice, record.orderPrice)
}

private fun isSameLeg(left: ConditionLeg, right: ConditionLeg): Boolean =
    left.side == right.side &&
        isSameAmount(left.triggerPrice, right.triggerPrice) &&
        isSameAmount(left.orderPrice, right.orderPrice)

// 금액은 자릿수가 달라도 값이 같으면 같음(70000 과 70000.00)
private fun isSameAmount(left: Money, right: Money?): Boolean =
    right != null && left.currency == right.currency && left.amount.compareTo(right.amount) == 0

/**
 * 조건주문 목록 범위.
 * 토스 `status` 조회값과 같음.
 */
enum class ConditionalOrderScope {
    /** 감시 중·일시중지·주문 진행 중. */
    OPEN,
    /** 완료·만료. */
    CLOSED,
}

/**
 * 조건주문 목록 조회.
 * [symbol] 이 없으면 모든 종목임.
 */
data class ConditionalOrdersQuery(
    val scope: ConditionalOrderScope,
    val symbol: Symbol?,
    val cursor: String?,
)
