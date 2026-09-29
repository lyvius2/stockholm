package banghak.stock.engine.application.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.trading.ManualTrigger
import banghak.stock.core.domain.trading.OrderIntent
import banghak.stock.core.domain.trading.RecommendationTrigger
import banghak.stock.core.port.TradingPort
import banghak.stock.core.usecase.EvaluateGuardrailUseCase
import banghak.stock.core.usecase.ManualOrderRequest
import banghak.stock.core.usecase.OrderPlacement
import banghak.stock.core.usecase.PlaceManualOrderUseCase
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 수동 주문을 가드레일 → 증권사 순서로 보냄.
 * 멱등 키는 화면이 주고, 같은 키의 두 번째 요청은 첫 요청의 결과를 돌려받음.
 * 결과를 모르면 [PendingSubmissionResolver] 가 주문 목록을 읽어 확인하고 다시 보내지 않음.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class ManualOrderService(
    private val guardrail: EvaluateGuardrailUseCase,
    private val trading: TradingPort,
    private val dispatcher: SubmissionDispatcher,
) : PlaceManualOrderUseCase {
    override fun place(request: ManualOrderRequest): OrderPlacement {
        val intent = request.intent
        val deviceId = deviceOf(intent)
        dispatcher
            .findPlacement(intent.userId, request.clientOrderId) { it.isSameOrderAs(intent) }
            ?.let {
                return it
            }
        val verdict = guardrail.evaluate(intent, request.clientOrderId)
        val submission =
            dispatcher.approve(
                deviceId,
                intent,
                request.clientOrderId,
                verdict,
                request.confirmedRules,
            )
        return dispatcher.dispatch(
            dispatcher.sendingRecord(submission, deviceId, replacesBrokerOrderId = null)
        ) {
            trading.placeOrder(submission)
        }
    }

    // 확인 창을 거친 주문 요청은 화면이 보낸 디바이스로 기록함
    private fun deviceOf(intent: OrderIntent): DeviceId =
        when (val trigger = intent.trigger) {
            is ManualTrigger -> trigger.device
            is RecommendationTrigger ->
                throw InvalidValueException("AI 추천 승인 주문은 추천 기능(4단계)과 함께 받음")
            else -> throw InvalidValueException("수동 주문이 아님: ${trigger::class.simpleName}")
        }
}
