package banghak.stock.engine.adapter.`in`.web.order

import banghak.stock.core.domain.account.Principal
import banghak.stock.core.usecase.AmendOrderRequest
import banghak.stock.core.usecase.AmendOrderUseCase
import banghak.stock.core.usecase.CancelOrderRequest
import banghak.stock.core.usecase.CancelOrderUseCase
import banghak.stock.core.usecase.ListOrdersUseCase
import banghak.stock.core.usecase.LookupOrderTicketUseCase
import banghak.stock.core.usecase.ManualOrderRequest
import banghak.stock.core.usecase.PlaceManualOrderUseCase
import banghak.stock.engine.adapter.`in`.web.common.SymbolDto
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 수동 주문(주문 모달·정정 모달).
 * 본인 세션의 사용자·디바이스로만 주문하고, 가드레일·증권사 호출은 usecase 안에서 함.
 */
@RestController
@RequestMapping("/orders")
@Profile(RuntimeProfiles.ENGINE)
class OrderController(
    private val tickets: LookupOrderTicketUseCase,
    private val placeOrder: PlaceManualOrderUseCase,
    private val amendOrder: AmendOrderUseCase,
    private val cancelOrder: CancelOrderUseCase,
    private val listOrders: ListOrdersUseCase,
    private val clock: Clock,
) {
    @GetMapping("/open")
    fun openOrders(principal: Principal): List<OrderListingResponse> =
        listOrders.openOrders(principal.userId).map(OrderListingResponse::of)

    @GetMapping("/today")
    fun todayClosedOrders(principal: Principal): List<OrderListingResponse> =
        listOrders.todayClosedOrders(principal.userId).map(OrderListingResponse::of)

    @GetMapping("/ticket")
    fun ticket(
        principal: Principal,
        @RequestParam market: String,
        @RequestParam code: String,
    ): OrderTicketResponse =
        OrderTicketResponse.of(tickets.ticket(principal.userId, SymbolDto(market, code).toSymbol()))

    @PostMapping
    fun place(principal: Principal, @RequestBody request: PlaceOrderRequest): PlacementResponse =
        PlacementResponse.of(
            placeOrder.place(
                ManualOrderRequest(
                    clientOrderIdOf(request.clientOrderId),
                    request.toIntent(principal, clock.instant()),
                    request.confirmedRules,
                )
            )
        )

    @PostMapping("/{brokerOrderId}/amend")
    fun amend(
        principal: Principal,
        @PathVariable brokerOrderId: String,
        @RequestBody request: AmendOrderBody,
    ): PlacementResponse =
        PlacementResponse.of(
            amendOrder.amend(
                AmendOrderRequest(
                    clientOrderId = clientOrderIdOf(request.clientOrderId),
                    userId = principal.userId,
                    deviceId = principal.session.deviceId,
                    brokerOrderId = brokerOrderId,
                    amendment = request.toAmendment(),
                    confirmedRules = request.confirmedRules,
                )
            )
        )

    @PostMapping("/{brokerOrderId}/cancel")
    fun cancel(principal: Principal, @PathVariable brokerOrderId: String): CancelResponse =
        CancelResponse.of(
            cancelOrder.cancel(
                CancelOrderRequest(principal.userId, principal.session.deviceId, brokerOrderId)
            )
        )
}
