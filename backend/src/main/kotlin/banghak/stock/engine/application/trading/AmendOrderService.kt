package banghak.stock.engine.application.trading

import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.trading.BrokerOrderRecord
import banghak.stock.core.domain.trading.ManualAmendTrigger
import banghak.stock.core.domain.trading.OrderAmendRequest
import banghak.stock.core.domain.trading.OrderAmendment
import banghak.stock.core.domain.trading.OrderIntent
import banghak.stock.core.domain.trading.OrderKind
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.SubmissionRecord
import banghak.stock.core.port.BrokerOrderStorePort
import banghak.stock.core.port.TradingPort
import banghak.stock.core.usecase.AmendOrderRequest
import banghak.stock.core.usecase.AmendOrderUseCase
import banghak.stock.core.usecase.EvaluateGuardrailUseCase
import banghak.stock.core.usecase.OrderPlacement
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 미체결 정정.
 * 원주문 상태는 로컬 기록이 아니라 증권사에서 그 자리에서 받아 판단함.
 * 정정 결과를 모르면 새 주문과 같은 방법(주문 목록 읽기)으로 정정으로 생긴 새 주문을 찾음.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class AmendOrderService(
    private val guardrail: EvaluateGuardrailUseCase,
    private val trading: TradingPort,
    private val orders: BrokerOrderStorePort,
    private val dispatcher: SubmissionDispatcher,
    private val clock: Clock,
) : AmendOrderUseCase {
    override fun amend(request: AmendOrderRequest): OrderPlacement {
        val sameAmendment = { stored: SubmissionRecord ->
            stored.isSameAmendmentAs(request.brokerOrderId, request.amendment)
        }
        dispatcher.findPlacement(request.userId, request.clientOrderId, sameAmendment)?.let {
            return it
        }
        val original = trading.lookupOrder(request.userId, request.brokerOrderId)
        val amendment =
            OrderAmendment.forMarket(
                original.symbol.market,
                request.amendment.newLimitPrice,
                request.amendment.newQuantity,
            )
        original.requireAmendable(amendment)
        val intent = amendedIntent(request, original, amendment)
        val verdict = guardrail.evaluate(intent, request.clientOrderId)
        val submission =
            dispatcher.approve(
                request.deviceId,
                intent,
                request.clientOrderId,
                verdict,
                request.confirmedRules,
            )
        val brokerRequest =
            OrderAmendRequest(
                userId = request.userId,
                brokerOrderId = original.brokerOrderId,
                symbol = original.symbol,
                limitPrice = requireNotNull(intent.limitPrice),
                quantity = if (original.symbol.market == Market.KR) intent.quantity else null,
                isHighValueConfirmed = submission.isHighValueConfirmed,
            )
        val record = dispatcher.sendingRecord(submission, request.deviceId, original.brokerOrderId)
        return dispatcher.dispatch(record) { trading.placeAmendment(brokerRequest) }
    }

    // 국내 정정은 가격만 바꿀 때도 수량을 보내야 해서 잔량을 보냄(잔량 전체를 새 가격으로).
    // 토스 규격은 "변경할 수량"이며 국내 관행상 잔량 중 정정할 수량임.
    // 출처는 원주문을 이어받아 자동 주문의 정정도 노출액에 계속 잡힘
    private fun amendedIntent(
        request: AmendOrderRequest,
        original: BrokerOrderRecord,
        amendment: OrderAmendment,
    ): OrderIntent =
        OrderIntent(
            userId = request.userId,
            symbol = original.symbol,
            side = original.side,
            kind = OrderKind.LIMIT,
            timeInForce = original.timeInForce,
            limitPrice = amendment.newLimitPrice ?: original.limitPrice,
            quantity = amendment.newQuantity ?: original.remaining(),
            orderAmount = null,
            origin =
                orders.findOrigin(request.userId, original.brokerOrderId) ?: OrderOrigin.MANUAL,
            trigger = ManualAmendTrigger(request.deviceId, original.brokerOrderId),
            intendedAt = clock.instant(),
        )
}
