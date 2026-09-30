package banghak.stock.engine.application.trading

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.ConditionalOrdersPage
import banghak.stock.core.domain.trading.ConditionalOrdersQuery
import banghak.stock.core.port.ConditionalOrderPort
import banghak.stock.core.usecase.ListConditionalOrdersUseCase
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 조건주문 목록은 토스가 원본이라 저장하지 않고 그 자리에서 읽음.
 * 토스 앱에서 등록한 조건주문도 함께 보임.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class ConditionalOrderQueryService(private val conditionalOrders: ConditionalOrderPort) :
    ListConditionalOrdersUseCase {
    override fun list(userId: UserId, query: ConditionalOrdersQuery): ConditionalOrdersPage =
        conditionalOrders.conditionalOrders(userId, query)
}
