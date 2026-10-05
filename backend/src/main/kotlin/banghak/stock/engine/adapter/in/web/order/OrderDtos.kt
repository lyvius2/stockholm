package banghak.stock.engine.adapter.`in`.web.order

import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.ManualTrigger
import banghak.stock.core.domain.trading.OrderAmendment
import banghak.stock.core.domain.trading.OrderIntent
import banghak.stock.core.domain.trading.OrderKind
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.OrderTicket
import banghak.stock.core.domain.trading.PriceLimits
import banghak.stock.core.domain.trading.TimeInForce
import banghak.stock.core.usecase.CancelPlacement
import banghak.stock.core.usecase.OrderPlacement
import banghak.stock.engine.adapter.`in`.web.common.MoneyDto
import banghak.stock.engine.adapter.`in`.web.common.SymbolDto
import banghak.stock.engine.adapter.`in`.web.common.enumOf
import banghak.stock.engine.adapter.`in`.web.common.quantityOf
import banghak.stock.engine.adapter.`in`.web.common.toPlainString
import java.time.Instant

/**
 * 주문 모달의 주문 가능 정보.
 * 상하한가·수수료율은 받지 못하면 null.
 */
data class OrderTicketResponse(
    val symbol: SymbolDto,
    val buyingPower: MoneyDto,
    val sellableQuantity: String,
    val priceLimits: PriceLimitsDto?,
    val commissionRate: String?,
) {
    companion object {
        fun of(ticket: OrderTicket) =
            OrderTicketResponse(
                SymbolDto.of(ticket.symbol),
                MoneyDto.of(ticket.buyingPower),
                ticket.sellableQuantity.toString(),
                ticket.priceLimits?.let(PriceLimitsDto::of),
                ticket.commissionRate?.toPlainString(),
            )
    }
}

data class PriceLimitsDto(val upper: MoneyDto?, val lower: MoneyDto?, val asOf: Instant) {
    companion object {
        fun of(limits: PriceLimits) =
            PriceLimitsDto(
                limits.upper?.let(MoneyDto::of),
                limits.lower?.let(MoneyDto::of),
                limits.asOf,
            )
    }
}

/**
 * 수동 주문 요청.
 * clientOrderId 는 화면이 제출 버튼을 누를 때 한 번 만든 ULID 이며 재시도에도 같은 값을 보냄.
 * confirmedRules 는 확인 창에서 사람이 확인한 가드레일 노트의 규칙 이름임.
 */
data class PlaceOrderRequest(
    val clientOrderId: String = "",
    val symbol: SymbolDto = SymbolDto(),
    val side: String = "",
    val kind: String = "",
    val timeInForce: String = "DAY",
    val limitPrice: MoneyDto? = null,
    val quantity: String? = null,
    val orderAmount: MoneyDto? = null,
    val confirmedRules: Set<String> = emptySet(),
) {
    fun toIntent(principal: Principal, intendedAt: Instant) =
        OrderIntent(
            userId = principal.userId,
            symbol = symbol.toSymbol(),
            side = enumOf<OrderSide>(side, "매매 방향"),
            kind = enumOf<OrderKind>(kind, "호가 유형"),
            timeInForce = enumOf<TimeInForce>(timeInForce, "유효 조건"),
            limitPrice = limitPrice?.toMoney(),
            quantity = quantity?.let(::quantityOf),
            orderAmount = orderAmount?.toMoney(),
            origin = OrderOrigin.MANUAL,
            trigger = ManualTrigger(principal.session.deviceId),
            intendedAt = intendedAt,
        )
}

/**
 * 정정 요청.
 * 국내는 가격·수량, 미국은 가격만(토스 규격).
 */
data class AmendOrderBody(
    val clientOrderId: String = "",
    val newLimitPrice: MoneyDto? = null,
    val newQuantity: String? = null,
    val confirmedRules: Set<String> = emptySet(),
) {
    fun toAmendment() = OrderAmendment(newLimitPrice?.toMoney(), newQuantity?.let(::quantityOf))
}

/**
 * 주문·정정을 보낸 결과.
 * PENDING 은 확인 중이라 화면이 잠그고, NEEDS_REVIEW 는 사람이 토스에서 확인함.
 */
data class PlacementResponse(
    val clientOrderId: String,
    val state: String,
    val brokerOrderId: String?,
) {
    companion object {
        fun of(placement: OrderPlacement) =
            when (placement) {
                is OrderPlacement.Accepted ->
                    PlacementResponse(
                        placement.clientOrderId.value,
                        "ACCEPTED",
                        placement.brokerOrderId,
                    )
                is OrderPlacement.Pending ->
                    PlacementResponse(placement.clientOrderId.value, "PENDING", null)
                is OrderPlacement.NeedsReview ->
                    PlacementResponse(placement.clientOrderId.value, "NEEDS_REVIEW", null)
            }
    }
}

/**
 * 취소 요청의 결과.
 * 원주문의 최종 상태는 실시간 주문 채널로 옴.
 */
data class CancelResponse(val state: String, val cancelBrokerOrderId: String?) {
    companion object {
        fun of(placement: CancelPlacement) =
            when (placement) {
                is CancelPlacement.Requested ->
                    CancelResponse("REQUESTED", placement.cancelBrokerOrderId)
                CancelPlacement.Pending -> CancelResponse("PENDING", null)
            }
    }
}

fun clientOrderIdOf(text: String): ClientOrderId = ClientOrderId(text)
