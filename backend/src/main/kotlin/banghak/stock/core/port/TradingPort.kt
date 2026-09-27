package banghak.stock.core.port

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.portfolio.PortfolioSnapshot
import banghak.stock.core.domain.trading.BrokerOrder
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.Fill
import banghak.stock.core.domain.trading.OrderAmendment
import banghak.stock.core.domain.trading.OrderIntent
import java.time.LocalDate

/**
 * 증권사 주문·계좌 포트.
 * 구현은 토스증권 어댑터 하나뿐이며 항상 해당 사용자 본인의 키로 부름.
 * 결과를 모르는 요청(타임아웃)은 [banghak.stock.core.domain.error.OrderResultUnknownException] 으로 올리고, 호출자는
 * [lookup] 으로 확정하기 전에 재시도하지 않음.
 */
interface TradingPort {
    fun submit(intent: OrderIntent, clientOrderId: ClientOrderId): BrokerOrder

    /**
     * 정정.
     * 토스가 새 주문 번호를 발급하므로 돌려주는 주문의 `replacesBrokerOrderId` 가 원주문임.
     */
    fun amend(userId: UserId, brokerOrderId: String, amendment: OrderAmendment): BrokerOrder

    fun cancel(userId: UserId, brokerOrderId: String): BrokerOrder

    fun lookup(userId: UserId, brokerOrderId: String): BrokerOrder

    fun openOrders(userId: UserId, market: Market): List<BrokerOrder>

    fun closedOrders(
        userId: UserId,
        market: Market,
        from: LocalDate,
        to: LocalDate,
        cursor: String?,
    ): ClosedOrdersPage

    fun fills(userId: UserId, market: Market, day: LocalDate): List<Fill>

    fun snapshot(userId: UserId, market: Market): PortfolioSnapshot
}

/**
 * 종료 주문 한 페이지.
 * [nextCursor] 가 null 이면 끝.
 */
data class ClosedOrdersPage(val orders: List<BrokerOrder>, val nextCursor: String?)
