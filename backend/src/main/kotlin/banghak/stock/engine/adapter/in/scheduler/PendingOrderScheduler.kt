package banghak.stock.engine.adapter.`in`.scheduler

import banghak.stock.core.usecase.ResolvePendingConditionalOrdersUseCase
import banghak.stock.core.usecase.ResolvePendingOrdersUseCase
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 결과를 모르는 주문·조건주문을 몇 초마다 확인함.
 * 확인은 목록을 읽기만 하며, 목록 조회 한도(주문 초당 5회)를 나눠 쓰므로 간격을 너무 좁히지 않음.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class PendingOrderScheduler(
    private val orders: ResolvePendingOrdersUseCase,
    private val conditionalOrders: ResolvePendingConditionalOrdersUseCase,
) {
    @Scheduled(fixedDelayString = "\${stockholm.orders.pending-check-interval:PT5S}")
    fun resolvePending() {
        orders.resolvePending()
        conditionalOrders.resolvePending()
    }
}
