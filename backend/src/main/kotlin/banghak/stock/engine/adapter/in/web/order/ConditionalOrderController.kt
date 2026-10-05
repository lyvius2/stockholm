package banghak.stock.engine.adapter.`in`.web.order

import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.ConditionalOrderScope
import banghak.stock.core.domain.trading.ConditionalOrdersQuery
import banghak.stock.core.usecase.AmendConditionalOrderRequest
import banghak.stock.core.usecase.AmendConditionalOrderUseCase
import banghak.stock.core.usecase.CancelConditionalOrderRequest
import banghak.stock.core.usecase.CancelConditionalOrderUseCase
import banghak.stock.core.usecase.ListConditionalOrdersUseCase
import banghak.stock.core.usecase.RegisterConditionalOrderRequest
import banghak.stock.core.usecase.RegisterConditionalOrderUseCase
import banghak.stock.engine.adapter.`in`.web.common.SymbolDto
import banghak.stock.engine.adapter.`in`.web.common.enumOf
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
 * 조건주문(SINGLE·OCO·OTO).
 * 감시와 발동은 토스 서버가 하므로 가드레일은 등록·수정 순간에만 usecase 가 거침.
 */
@RestController
@RequestMapping("/conditional-orders")
@Profile(RuntimeProfiles.ENGINE)
class ConditionalOrderController(
    private val listing: ListConditionalOrdersUseCase,
    private val register: RegisterConditionalOrderUseCase,
    private val amend: AmendConditionalOrderUseCase,
    private val cancel: CancelConditionalOrderUseCase,
    private val clock: Clock,
) {
    @GetMapping
    fun list(
        principal: Principal,
        @RequestParam(defaultValue = "OPEN") scope: String,
        @RequestParam(required = false) market: String?,
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) cursor: String?,
    ): ConditionalOrdersResponse =
        ConditionalOrdersResponse.of(
            listing.list(
                principal.userId,
                ConditionalOrdersQuery(
                    enumOf<ConditionalOrderScope>(scope, "조회 범위"),
                    symbolOf(market, code),
                    cursor,
                ),
            )
        )

    @PostMapping
    fun register(
        principal: Principal,
        @RequestBody request: ConditionalOrderRequest,
    ): ConditionalPlacementResponse =
        ConditionalPlacementResponse.of(
            register.register(
                RegisterConditionalOrderRequest(
                    clientOrderIdOf(request.clientOrderId),
                    request.toIntent(principal, clock.instant()),
                    request.confirmedRules,
                )
            )
        )

    @PostMapping("/{conditionalOrderId}/amend")
    fun amend(
        principal: Principal,
        @PathVariable conditionalOrderId: String,
        @RequestBody request: ConditionalOrderRequest,
    ): ConditionalPlacementResponse =
        ConditionalPlacementResponse.of(
            amend.amend(
                AmendConditionalOrderRequest(
                    clientOrderIdOf(request.clientOrderId),
                    conditionalOrderId,
                    request.toIntent(principal, clock.instant()),
                    request.confirmedRules,
                )
            )
        )

    @PostMapping("/{conditionalOrderId}/cancel")
    fun cancel(
        principal: Principal,
        @PathVariable conditionalOrderId: String,
    ): ConditionalCancelResponse =
        ConditionalCancelResponse.of(
            cancel.cancel(
                CancelConditionalOrderRequest(
                    principal.userId,
                    principal.session.deviceId,
                    conditionalOrderId,
                )
            )
        )

    // 종목으로 거르려면 시장과 코드가 함께 있어야 함
    private fun symbolOf(market: String?, code: String?): Symbol? =
        when {
            market == null && code == null -> null
            market != null && code != null -> SymbolDto(market, code).toSymbol()
            else -> throw InvalidValueException("종목으로 거르려면 market 과 code 를 함께 줄 것")
        }
}
