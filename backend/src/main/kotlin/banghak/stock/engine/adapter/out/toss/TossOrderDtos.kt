package banghak.stock.engine.adapter.out.toss

import com.fasterxml.jackson.annotation.JsonInclude
import java.math.BigDecimal

/**
 * 주문 생성 본문.
 * 지정가(수량·가격)와 금액 시장가(orderAmount) 두 형태이며 빈 필드는 보내지 않음.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TossOrderRequest(
    val clientOrderId: String,
    val symbol: String,
    val side: String,
    val orderType: String,
    val timeInForce: String?,
    val quantity: String?,
    val price: String?,
    val orderAmount: String?,
    val confirmHighValueOrder: Boolean?,
)

/**
 * 정정 본문.
 * 우리는 지정가만 씀.
 * 국내는 수량 필수, 미국은 수량을 보내면 400 임.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TossModifyRequest(
    val orderType: String,
    val quantity: String?,
    val price: String,
    val confirmHighValueOrder: Boolean?,
)

data class TossOrderReceipt(val orderId: String = "", val clientOrderId: String? = null)

data class TossExecution(
    val filledQuantity: BigDecimal = BigDecimal.ZERO,
    val averageFilledPrice: BigDecimal? = null,
    val filledAmount: BigDecimal? = null,
    val commission: BigDecimal? = null,
    val tax: BigDecimal? = null,
    val filledAt: String? = null,
)

data class TossOrder(
    val orderId: String = "",
    val symbol: String = "",
    val side: String = "",
    val orderType: String = "",
    val timeInForce: String = "",
    val status: String = "",
    val price: BigDecimal? = null,
    val quantity: BigDecimal? = null,
    val orderAmount: BigDecimal? = null,
    val currency: String = "",
    val orderedAt: String = "",
    val canceledAt: String? = null,
    val execution: TossExecution? = null,
)

data class TossOrders(val orders: List<TossOrder> = emptyList(), val nextCursor: String? = null)

/**
 * 계좌 목록.
 * 계좌번호(accountNo)는 받지도 않음(로그·저장 금지).
 */
data class TossAccount(val accountSeq: Long = 0, val accountType: String = "")

data class TossCurrencyAmount(val krw: BigDecimal? = null, val usd: BigDecimal? = null)

data class TossHoldingsValue(val amount: TossCurrencyAmount = TossCurrencyAmount())

data class TossHoldingValue(
    val purchaseAmount: BigDecimal = BigDecimal.ZERO,
    val amount: BigDecimal = BigDecimal.ZERO,
)

data class TossHoldingProfit(val amount: BigDecimal = BigDecimal.ZERO)

data class TossHoldingItem(
    val symbol: String = "",
    val marketCountry: String = "",
    val currency: String = "",
    val quantity: BigDecimal = BigDecimal.ZERO,
    val lastPrice: BigDecimal = BigDecimal.ZERO,
    val averagePurchasePrice: BigDecimal = BigDecimal.ZERO,
    val marketValue: TossHoldingValue = TossHoldingValue(),
    val profitLoss: TossHoldingProfit = TossHoldingProfit(),
)

data class TossHoldings(
    val marketValue: TossHoldingsValue = TossHoldingsValue(),
    val items: List<TossHoldingItem> = emptyList(),
)

data class TossBuyingPower(
    val currency: String = "",
    val cashBuyingPower: BigDecimal = BigDecimal.ZERO,
)

/**
 * 조건주문 감시 조건 본문.
 * 우리는 지정가만 쓰므로 주문 가격을 늘 보냄.
 */
data class TossConditionRequest(
    val orderSide: String,
    val triggerPrice: String,
    val orderPrice: String,
)

/**
 * 조건주문 등록 본문.
 * SINGLE 은 둘째 조건을 보내지 않음.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TossConditionalOrderRequest(
    val symbol: String,
    val type: String,
    val quantity: String,
    val orderType: String,
    val clientOrderId: String,
    val expireDate: String,
    val first: TossConditionRequest,
    val second: TossConditionRequest?,
    val confirmHighValueOrder: Boolean?,
)

/**
 * 조건주문 수정 본문.
 * 전체를 다시 설정하며 종목과 멱등 키는 없음.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TossConditionalModifyRequest(
    val type: String,
    val quantity: String,
    val orderType: String,
    val expireDate: String,
    val first: TossConditionRequest,
    val second: TossConditionRequest?,
    val confirmHighValueOrder: Boolean?,
)

data class TossConditionalOrderReceipt(
    val conditionalOrderId: String = "",
    val clientOrderId: String? = null,
)

/**
 * 조건주문 조회의 감시 조건.
 * 매매 방향은 오지 않음.
 */
data class TossCondition(
    val type: String = "",
    val status: String = "",
    val triggerPrice: BigDecimal? = null,
    val targetProfitRate: BigDecimal? = null,
    val orderPrice: BigDecimal? = null,
    val triggeredOrderId: String? = null,
)

data class TossConditionalOrder(
    val conditionalOrderId: String = "",
    val type: String = "",
    val status: String = "",
    val symbol: String = "",
    val market: String = "",
    val quantity: BigDecimal = BigDecimal.ZERO,
    val orderType: String = "",
    val expireDate: String? = null,
    val first: TossCondition = TossCondition(),
    val second: TossCondition? = null,
    val createdAt: String = "",
)

data class TossConditionalOrders(
    val conditionalOrders: List<TossConditionalOrder> = emptyList(),
    val nextCursor: String? = null,
    val hasNext: Boolean = false,
)
