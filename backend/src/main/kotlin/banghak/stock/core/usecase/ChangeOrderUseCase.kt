package banghak.stock.core.usecase

import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.OrderAmendment

/**
 * 사람이 미체결 주문을 정정하는 요청.
 * [clientOrderId] 는 화면이 정정 버튼을 누를 때 한 번 만든 값이며, 정정으로 생길 새 주문을 원주문과 잇는 로컬 키임.
 * 토스 정정 요청에는 멱등 키가 없어 이 값은 토스로 가지 않음.
 */
data class AmendOrderRequest(
    val clientOrderId: ClientOrderId,
    val userId: UserId,
    val deviceId: DeviceId,
    val brokerOrderId: String,
    val amendment: OrderAmendment,
    val confirmedRules: Set<String>,
)

/**
 * 미체결 정정.
 * 자동 주문의 정정도 사람이 하면 수동 주문으로 취급하고 출처는 원주문을 이어받음.
 */
interface AmendOrderUseCase {
    /**
     * 증권사의 원주문 상태를 확인한 뒤 가드레일을 거쳐 정정을 보냄.
     * 같은 키의 요청이 이미 있으면 그 결과를 돌려줌.
     *
     * @param request 정정 요청
     * @return 새 주문이 접수됐거나 확인 중이거나 사람 확인이 필요한 정정
     * @throws banghak.stock.core.domain.error.InvalidValueException 정정할 수 없는 주문·수량이면 발생함
     * @throws banghak.stock.core.domain.error.GuardrailViolationException 가드레일이 거부하면 발생함
     * @throws banghak.stock.core.domain.error.ConfirmationRequiredException 확인이 필요한 노트를 사람이 확인하지
     * 않았으면 발생함
     * @throws banghak.stock.core.domain.error.OrderRejectedException 증권사가 받지 않았으면 발생함
     * @throws banghak.stock.core.domain.error.BrokerUnavailableException 증권사에 닿기 전에 실패했으면 발생함
     */
    fun amend(request: AmendOrderRequest): OrderPlacement
}

data class CancelOrderRequest(val userId: UserId, val deviceId: DeviceId, val brokerOrderId: String)

/**
 * 취소 요청의 결과.
 * 원주문의 최종 상태는 실시간 주문 채널로 옴.
 */
sealed interface CancelPlacement {
    /**
     * 증권사가 취소 요청을 받음.
     * 토스는 취소 요청에도 새 주문 번호를 줌.
     */
    data class Requested(val cancelBrokerOrderId: String) : CancelPlacement

    /**
     * 받았는지 모름.
     * 실시간 주문 채널·재동기가 원주문 상태를 알려 줌.
     */
    data object Pending : CancelPlacement
}

/**
 * 미체결 취소.
 * 위험을 줄이는 조작이라 가드레일을 거치지 않음.
 * 토스는 이미 취소·정정된 주문의 취소를 409 로 거부하므로 다시 눌러도 주문이 생기지 않음.
 */
interface CancelOrderUseCase {
    /**
     * @param request 취소 요청
     * @return 받았거나 확인 중인 취소
     * @throws banghak.stock.core.domain.error.InvalidValueException 취소할 수 없는 상태면 발생함
     * @throws banghak.stock.core.domain.error.OrderRejectedException 증권사가 받지 않았으면 발생함
     */
    fun cancel(request: CancelOrderRequest): CancelPlacement
}
