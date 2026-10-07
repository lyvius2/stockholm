package banghak.stock.engine.application.trading

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.OrderListing
import banghak.stock.core.port.BrokerOrderStorePort
import banghak.stock.core.usecase.ListOrdersUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import java.time.ZoneId
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 미체결·오늘 체결 목록.
 * "오늘" 은 한국 시간 0시부터이며 미국 장의 밤 체결도 그날로 묶임(거래내역 패널의 ET 기준과는 다름).
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class OrderListingService(private val orders: BrokerOrderStorePort, private val clock: Clock) :
    ListOrdersUseCase {
    override fun openOrders(userId: UserId): List<OrderListing> =
        orders.findOpen(userId).sortedByDescending { it.orderedAt }

    override fun todayClosedOrders(userId: UserId): List<OrderListing> {
        val todayStart = clock.instant().atZone(KST).toLocalDate().atStartOfDay(KST).toInstant()
        return orders.findClosedSince(userId, todayStart).sortedByDescending {
            it.closedOrUpdatedAt
        }
    }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
