package banghak.stock.engine.application.trading

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.BrokerOrderRecord
import banghak.stock.core.domain.trading.ClosedOrdersQuery
import banghak.stock.core.port.TradingPort

/**
 * 종료 주문을 여러 쪽 읽은 결과.
 * [isTruncated] 면 쪽 수 상한에 걸려 뒤가 남았음.
 */
internal data class ClosedOrders(val orders: List<BrokerOrderRecord>, val isTruncated: Boolean)

/** 토스 종료 주문은 커서로 한 쪽에 100건씩 오므로 [maxPages] 쪽까지 이어 읽음. */
internal fun TradingPort.closedOrderPages(
    userId: UserId,
    first: ClosedOrdersQuery,
    maxPages: Int,
): ClosedOrders {
    val found = mutableListOf<BrokerOrderRecord>()
    var query: ClosedOrdersQuery = first
    repeat(maxPages) {
        val page = closedOrders(userId, query)
        found += page.orders
        val next = page.nextCursor ?: return ClosedOrders(found, isTruncated = false)
        query = first.copy(cursor = next)
    }
    return ClosedOrders(found, isTruncated = true)
}
