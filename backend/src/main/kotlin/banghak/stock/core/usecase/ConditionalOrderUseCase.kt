package banghak.stock.core.usecase

import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.ConditionalOrderIntent
import banghak.stock.core.domain.trading.ConditionalOrdersPage
import banghak.stock.core.domain.trading.ConditionalOrdersQuery

/**
 * 사람이 조건주문을 등록하는 요청.
 * [clientOrderId] 는 화면이 등록 버튼을 누를 때 한 번 만든 값이며 토스 멱등 키로도 씀.
 * [confirmedRules] 는 확인 창에서 사람이 확인한 가드레일 노트의 규칙 이름임.
 */
data class RegisterConditionalOrderRequest(
    val clientOrderId: ClientOrderId,
    val intent: ConditionalOrderIntent,
    val confirmedRules: Set<String>,
)

/**
 * 사람이 조건주문을 다시 설정하는 요청.
 * 토스 수정 요청에는 멱등 키가 없어 [clientOrderId] 는 이중 클릭을 막는 로컬 키로만 씀.
 */
data class AmendConditionalOrderRequest(
    val clientOrderId: ClientOrderId,
    val conditionalOrderId: String,
    val intent: ConditionalOrderIntent,
    val confirmedRules: Set<String>,
)

data class CancelConditionalOrderRequest(
    val userId: UserId,
    val deviceId: DeviceId,
    val conditionalOrderId: String,
)

/** 조건주문 등록·수정을 증권사에 보낸 결과. */
sealed interface ConditionalPlacement {
    val clientOrderId: ClientOrderId

    data class Registered(
        override val clientOrderId: ClientOrderId,
        val conditionalOrderId: String,
    ) : ConditionalPlacement

    /**
     * 접수 여부를 모름.
     * 조건주문 목록을 읽어 확인하는 동안 화면은 "확인 중"으로 잠금.
     */
    data class Pending(override val clientOrderId: ClientOrderId) : ConditionalPlacement

    /**
     * 정해진 시간 안에 확인하지 못함.
     * 사람이 토스 조건주문 목록에서 확인해야 함.
     */
    data class NeedsReview(override val clientOrderId: ClientOrderId) : ConditionalPlacement
}

/** 조건주문 취소 요청의 결과. */
sealed interface ConditionalCancelPlacement {
    data object Canceled : ConditionalCancelPlacement

    /**
     * 받았는지 모름.
     * 조건주문 목록에 남아 있는지로 확인함.
     */
    data object Pending : ConditionalCancelPlacement
}

/** 조건주문 등록(F1 조건 주문 탭). */
interface RegisterConditionalOrderUseCase {
    /**
     * 가드레일을 통과한 조건주문만 증권사에 등록함.
     * 같은 멱등 키의 요청이 이미 있으면 가드레일·증권사를 거치지 않고 그 결과를 돌려줌.
     *
     * @param request 멱등 키, 조건주문 의도, 사람이 확인한 노트
     * @return 등록됐거나 확인 중이거나 사람 확인이 필요한 조건주문
     * @throws banghak.stock.core.domain.error.GuardrailViolationException 가드레일이 거부하면 발생함
     * @throws banghak.stock.core.domain.error.ConfirmationRequiredException 확인이 필요한 노트를 사람이 확인하지
     * 않았으면 발생함
     * @throws banghak.stock.core.domain.error.OrderRejectedException 증권사가 받지 않았으면 발생함
     * @throws banghak.stock.core.domain.error.BrokerUnavailableException 증권사에 닿기 전에 실패했으면 발생함
     */
    fun register(request: RegisterConditionalOrderRequest): ConditionalPlacement
}

/**
 * 조건주문 수정.
 * 토스가 기존 것을 취소하고 새 번호로 다시 만듦.
 */
interface AmendConditionalOrderUseCase {
    /**
     * 증권사의 기존 조건주문을 확인한 뒤 가드레일을 거쳐 수정을 보냄.
     * 같은 키의 요청이 이미 있으면 그 결과를 돌려줌.
     *
     * @param request 수정 요청
     * @return 새 조건주문이 등록됐거나 확인 중이거나 사람 확인이 필요한 수정
     * @throws banghak.stock.core.domain.error.InvalidValueException 이미 끝났거나 종목이 다른 조건주문이면 발생함
     * @throws banghak.stock.core.domain.error.GuardrailViolationException 가드레일이 거부하면 발생함
     * @throws banghak.stock.core.domain.error.ConfirmationRequiredException 확인이 필요한 노트를 사람이 확인하지
     * 않았으면 발생함
     * @throws banghak.stock.core.domain.error.OrderRejectedException 증권사가 받지 않았으면 발생함
     * @throws banghak.stock.core.domain.error.BrokerUnavailableException 증권사에 닿기 전에 실패했으면 발생함
     */
    fun amend(request: AmendConditionalOrderRequest): ConditionalPlacement
}

/**
 * 조건주문 취소.
 * 위험을 줄이는 조작이라 가드레일을 거치지 않음.
 */
interface CancelConditionalOrderUseCase {
    /**
     * @param request 취소 요청
     * @return 취소됐거나 확인 중인 취소
     * @throws banghak.stock.core.domain.error.OrderRejectedException 증권사가 받지 않았으면 발생함(없는 조건주문 등)
     * @throws banghak.stock.core.domain.error.BrokerUnavailableException 증권사에 닿기 전에 실패했으면 발생함
     */
    fun cancel(request: CancelConditionalOrderRequest): ConditionalCancelPlacement
}

/**
 * 조건주문 목록.
 * 토스 앱에서 등록한 조건주문도 함께 보임.
 */
interface ListConditionalOrdersUseCase {
    fun list(userId: UserId, query: ConditionalOrdersQuery): ConditionalOrdersPage
}

/**
 * 결과를 모르는 조건주문 등록·수정 확인.
 * 조건주문 목록을 읽기만 하며 다시 보내지 않음.
 */
interface ResolvePendingConditionalOrdersUseCase {
    fun resolvePending()
}
