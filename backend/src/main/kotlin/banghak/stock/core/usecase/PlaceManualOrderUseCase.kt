package banghak.stock.core.usecase

import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.OrderIntent

/**
 * 사람이 낸 주문 요청.
 * [clientOrderId] 는 화면이 제출 버튼을 누를 때 한 번 만든 값이며 같은 제출을 다시 보내도 같은 값임.
 * 이 값이 곧 토스 멱등 키이고, 같은 값의 두 번째 요청은 새 주문이 아니라 첫 요청의 결과를 돌려받음.
 * [confirmedRules] 는 확인 창에서 사람이 확인한 가드레일 노트의 규칙 이름임.
 */
data class ManualOrderRequest(
    val clientOrderId: ClientOrderId,
    val intent: OrderIntent,
    val confirmedRules: Set<String>,
)

/** 주문을 증권사에 보낸 결과. */
sealed interface OrderPlacement {
    val clientOrderId: ClientOrderId

    /**
     * 증권사가 접수함.
     * 상태는 실시간 주문 채널로 바뀜.
     */
    data class Accepted(override val clientOrderId: ClientOrderId, val brokerOrderId: String) :
        OrderPlacement

    /**
     * 접수 여부를 모름(타임아웃 등).
     * 증권사 주문 목록을 읽어 확인하는 동안 화면은 "확인 중"으로 잠금.
     */
    data class Pending(override val clientOrderId: ClientOrderId) : OrderPlacement

    /**
     * 정해진 시간 안에 확인하지 못함.
     * 사람이 토스에서 주문 여부를 확인해야 함.
     */
    data class NeedsReview(override val clientOrderId: ClientOrderId) : OrderPlacement
}

/** 수동 주문(주문 모달·AI 추천 승인). */
interface PlaceManualOrderUseCase {
    /**
     * 가드레일을 통과한 주문만 증권사에 보냄.
     * 같은 멱등 키의 요청이 이미 있으면 가드레일·증권사를 거치지 않고 그 결과를 돌려줌.
     *
     * @param request 멱등 키, 주문 의도, 사람이 확인한 노트
     * @return 접수됐거나 확인 중이거나 사람 확인이 필요한 주문
     * @throws banghak.stock.core.domain.error.GuardrailViolationException 가드레일이 거부하면 발생함
     * @throws banghak.stock.core.domain.error.ConfirmationRequiredException 확인이 필요한 노트를 사람이 확인하지
     * 않았으면 발생함
     * @throws banghak.stock.core.domain.error.OrderRejectedException 증권사가 받지 않았으면 발생함
     * @throws banghak.stock.core.domain.error.BrokerUnavailableException 증권사에 닿기 전에 실패했으면 발생함(주문은
     * 나가지 않음)
     */
    fun place(request: ManualOrderRequest): OrderPlacement
}

/**
 * 결과를 모르는 주문 확인.
 * 증권사 주문 목록을 읽기만 하며 다시 보내지 않음.
 * 같은 키로 다시 보내면 첫 요청이 닿지 않았을 때 가드레일 없이 새 주문이 생기기 때문임.
 */
interface ResolvePendingOrdersUseCase {
    fun resolvePending()
}
