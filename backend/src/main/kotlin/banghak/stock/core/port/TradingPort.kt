package banghak.stock.core.port

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.portfolio.BrokerHoldings
import banghak.stock.core.domain.trading.BrokerOrderRecord
import banghak.stock.core.domain.trading.ClosedOrdersPage
import banghak.stock.core.domain.trading.ClosedOrdersQuery
import banghak.stock.core.domain.trading.OrderAmendRequest
import banghak.stock.core.domain.trading.OrderReceipt
import banghak.stock.core.domain.trading.OrderSubmission

/**
 * 증권사 주문·계좌 포트.
 * 구현은 토스증권 어댑터 하나뿐이며 항상 해당 사용자 본인의 키로 부름.
 * 증권사가 아는 사실만 돌려줌.
 * 출처·트리거·lot 과 합치는 일은 engine 이 함.
 * 결과를 모르는 요청(보낸 뒤 타임아웃·5xx)은 [banghak.stock.core.domain.error.OrderResultUnknownException] 으로 올리고,
 * 호출자는 [lookupOrder] 로 확정하기 전에 재시도하지 않음.
 * 주문·정정은 이름이 `place` 로 시작함.
 * 경계 테스트가 이 접두어로 가드레일을 거친 곳에서만 부르게 잠금.
 */
interface TradingPort {
    fun placeOrder(submission: OrderSubmission): OrderReceipt

    /**
     * 정정.
     * 토스가 새 주문 번호를 발급함.
     */
    fun placeAmendment(request: OrderAmendRequest): OrderReceipt

    /**
     * 취소.
     * 토스가 취소 요청 레코드의 새 주문 번호를 발급함.
     */
    fun cancelOrder(userId: UserId, brokerOrderId: String): OrderReceipt

    fun lookupOrder(userId: UserId, brokerOrderId: String): BrokerOrderRecord

    fun openOrders(userId: UserId, market: Market): List<BrokerOrderRecord>

    fun closedOrders(userId: UserId, query: ClosedOrdersQuery): ClosedOrdersPage

    fun holdings(userId: UserId): BrokerHoldings

    /** 통화별 현금 매수 가능 금액. */
    fun buyingPower(userId: UserId, currency: Currency): Money
}
