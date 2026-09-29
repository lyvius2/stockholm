package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import java.time.Instant
import java.time.LocalDate

/**
 * 주문 접수 결과.
 * 토스는 접수·정정·취소 응답에 주문 번호만 줌(상태·체결량 없음).
 * 정정·취소는 새 주문 번호를 발급하므로 [brokerOrderId] 는 원주문과 다름.
 */
data class OrderReceipt(val brokerOrderId: String, val clientOrderId: ClientOrderId?) {
    init {
        if (brokerOrderId.isBlank()) throw InvalidValueException("주문 번호가 비어 있음")
    }
}

/**
 * 증권사에 보낼 주문.
 * 가드레일을 지난 의도와 멱등 키, 1억원 이상 주문의 사람 확인 여부를 함께 담음.
 */
data class OrderSubmission(
    val intent: OrderIntent,
    val clientOrderId: ClientOrderId,
    val isHighValueConfirmed: Boolean,
)

/**
 * 증권사에 보낼 정정.
 * 토스 규격: 정정은 지정가만, 국내는 가격과 수량(정수)을 모두 보내고 미국은 가격만 보냄.
 * 화면의 정정 입력(가격만·수량만)을 원주문 값으로 채워 완성한 형태임.
 */
data class OrderAmendRequest(
    val userId: UserId,
    val brokerOrderId: String,
    val symbol: Symbol,
    val limitPrice: Money,
    val quantity: Quantity?,
    val isHighValueConfirmed: Boolean,
) {
    init {
        if (brokerOrderId.isBlank()) throw InvalidValueException("정정할 주문 번호가 비어 있음")
        if (limitPrice.currency != symbol.market.currency || !limitPrice.isPositive)
            throw InvalidValueException("정정 가격은 ${symbol.market.currency} 양수여야 함: $limitPrice")
        when (symbol.market) {
            Market.KR -> {
                val shares = quantity ?: throw InvalidValueException("국내 정정은 수량도 보내야 함")
                if (shares.isZero || !shares.isWholeShares)
                    throw InvalidValueException("국내 정정 수량은 1 이상 정수여야 함: $shares")
            }
            Market.US -> if (quantity != null) throw InvalidValueException("미국 주문은 가격만 정정할 수 있음")
        }
    }
}

/**
 * 증권사가 아는 주문 한 건.
 * 출처·트리거는 증권사가 모르므로 없음.
 * engine 이 로컬 주문 기록과 합쳐 [BrokerOrder] 를 만듦.
 * 토스는 체결을 건별로 주지 않아 주문 한 건에 체결 요약(평균가·금액·수수료·세금) 하나가 붙음.
 */
data class BrokerOrderRecord(
    val brokerOrderId: String,
    val symbol: Symbol,
    val side: OrderSide,
    val kind: OrderKind,
    val timeInForce: TimeInForce,
    val limitPrice: Money?,
    val quantity: Quantity?,
    val orderAmount: Money?,
    val status: OrderStatus,
    val filledQuantity: Quantity,
    val averageFilledPrice: Money?,
    val filledAmount: Money?,
    val fee: Money?,
    val tax: Money?,
    val orderedAt: Instant,
    val filledAt: Instant?,
    val canceledAt: Instant?,
) {
    init {
        if (brokerOrderId.isBlank()) throw InvalidValueException("주문 번호가 비어 있음")
    }

    val isOpen: Boolean
        get() = status.isOpen
}

/**
 * 종료 주문 조회 조건.
 * 날짜는 KST 주문일 기준이고 양 끝을 포함함.
 */
data class ClosedOrdersQuery(
    val market: Market,
    val from: LocalDate,
    val to: LocalDate,
    val cursor: String?,
) {
    init {
        if (from.isAfter(to)) throw InvalidValueException("조회 시작일이 종료일보다 늦음: $from~$to")
    }
}

/**
 * 종료 주문 한 페이지.
 * [nextCursor] 가 null 이면 끝.
 */
data class ClosedOrdersPage(val orders: List<BrokerOrderRecord>, val nextCursor: String?)
