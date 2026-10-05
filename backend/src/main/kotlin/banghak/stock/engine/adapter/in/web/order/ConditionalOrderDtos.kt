package banghak.stock.engine.adapter.`in`.web.order

import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.trading.ConditionLeg
import banghak.stock.core.domain.trading.ConditionLegRecord
import banghak.stock.core.domain.trading.ConditionalOrderIntent
import banghak.stock.core.domain.trading.ConditionalOrderRecord
import banghak.stock.core.domain.trading.ConditionalOrderType
import banghak.stock.core.domain.trading.ConditionalOrdersPage
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.usecase.ConditionalCancelPlacement
import banghak.stock.core.usecase.ConditionalPlacement
import banghak.stock.engine.adapter.`in`.web.common.MoneyDto
import banghak.stock.engine.adapter.`in`.web.common.SymbolDto
import banghak.stock.engine.adapter.`in`.web.common.enumOf
import banghak.stock.engine.adapter.`in`.web.common.quantityOf
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 조건주문 등록·수정 요청(F1 조건 주문 탭).
 * 지정가만 받으며 clientOrderId 는 화면이 버튼을 누를 때 한 번 만든 ULID 임.
 */
data class ConditionalOrderRequest(
    val clientOrderId: String = "",
    val symbol: SymbolDto = SymbolDto(),
    val type: String = "",
    val quantity: String = "",
    val first: ConditionLegDto = ConditionLegDto(),
    val second: ConditionLegDto? = null,
    val expireDate: String = "",
    val confirmedRules: Set<String> = emptySet(),
) {
    fun toIntent(principal: Principal, intendedAt: Instant) =
        ConditionalOrderIntent(
            userId = principal.userId,
            symbol = symbol.toSymbol(),
            type = enumOf<ConditionalOrderType>(type, "조건주문 타입"),
            quantity = quantityOf(quantity),
            first = first.toLeg(),
            second = second?.toLeg(),
            expireDate = localDateOf(expireDate),
            requestedBy = principal.session.deviceId,
            intendedAt = intendedAt,
        )

    private fun localDateOf(text: String): LocalDate =
        try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            throw InvalidValueException("만료일이 날짜(YYYY-MM-DD)가 아님: '$text'")
        }
}

data class ConditionLegDto(
    val side: String = "",
    val triggerPrice: MoneyDto = MoneyDto(),
    val orderPrice: MoneyDto = MoneyDto(),
) {
    fun toLeg() =
        ConditionLeg(enumOf<OrderSide>(side, "매매 방향"), triggerPrice.toMoney(), orderPrice.toMoney())
}

data class ConditionalPlacementResponse(
    val clientOrderId: String,
    val state: String,
    val conditionalOrderId: String?,
) {
    companion object {
        fun of(placement: ConditionalPlacement) =
            when (placement) {
                is ConditionalPlacement.Registered ->
                    ConditionalPlacementResponse(
                        placement.clientOrderId.value,
                        "REGISTERED",
                        placement.conditionalOrderId,
                    )
                is ConditionalPlacement.Pending ->
                    ConditionalPlacementResponse(placement.clientOrderId.value, "PENDING", null)
                is ConditionalPlacement.NeedsReview ->
                    ConditionalPlacementResponse(
                        placement.clientOrderId.value,
                        "NEEDS_REVIEW",
                        null,
                    )
            }
    }
}

data class ConditionalCancelResponse(val state: String) {
    companion object {
        fun of(placement: ConditionalCancelPlacement) =
            when (placement) {
                ConditionalCancelPlacement.Canceled -> ConditionalCancelResponse("CANCELED")
                ConditionalCancelPlacement.Pending -> ConditionalCancelResponse("PENDING")
            }
    }
}

/**
 * 조건주문 목록 한 쪽.
 * nextCursor 가 null 이면 끝.
 */
data class ConditionalOrdersResponse(
    val conditionalOrders: List<ConditionalOrderDto>,
    val nextCursor: String?,
) {
    companion object {
        fun of(page: ConditionalOrdersPage) =
            ConditionalOrdersResponse(
                page.conditionalOrders.map(ConditionalOrderDto::of),
                page.nextCursor,
            )
    }
}

data class ConditionalOrderDto(
    val conditionalOrderId: String,
    val type: String,
    val status: String,
    val symbol: SymbolDto,
    val quantity: String,
    val kind: String,
    val expireDate: String?,
    val first: ConditionLegRecordDto,
    val second: ConditionLegRecordDto?,
    val createdAt: Instant,
) {
    companion object {
        fun of(record: ConditionalOrderRecord) =
            ConditionalOrderDto(
                conditionalOrderId = record.conditionalOrderId,
                type = record.type.name,
                status = record.status.name,
                symbol = SymbolDto.of(record.symbol),
                quantity = record.quantity.toString(),
                kind = record.kind.name,
                expireDate = record.expireDate?.toString(),
                first = ConditionLegRecordDto.of(record.first),
                second = record.second?.let(ConditionLegRecordDto::of),
                createdAt = record.createdAt,
            )
    }
}

/**
 * 토스 조회에는 매매 방향이 없어 방향 필드가 없음.
 * 가격이 아닌 조건(앱 전용)은 감시가가 null.
 */
data class ConditionLegRecordDto(
    @get:JsonProperty("isPriceTrigger") val isPriceTrigger: Boolean,
    val status: String,
    val triggerPrice: MoneyDto?,
    val orderPrice: MoneyDto?,
    val triggeredOrderId: String?,
) {
    companion object {
        fun of(leg: ConditionLegRecord) =
            ConditionLegRecordDto(
                isPriceTrigger = leg.isPriceTrigger,
                status = leg.status.name,
                triggerPrice = leg.triggerPrice?.let(MoneyDto::of),
                orderPrice = leg.orderPrice?.let(MoneyDto::of),
                triggeredOrderId = leg.triggeredOrderId,
            )
    }
}
