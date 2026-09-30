package banghak.stock.engine.application.trading

import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.error.OrderRejectedException
import banghak.stock.core.domain.error.OrderResultUnknownException
import banghak.stock.core.domain.eventlog.ConditionalOrderCancelRequested
import banghak.stock.core.domain.eventlog.OrderRejected
import banghak.stock.core.domain.eventlog.OrderResultUnknown
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.port.ConditionalOrderPort
import banghak.stock.core.usecase.CancelConditionalOrderRequest
import banghak.stock.core.usecase.CancelConditionalOrderUseCase
import banghak.stock.core.usecase.ConditionalCancelPlacement
import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.crypto.UlidGenerator
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 조건주문 취소.
 * 결과를 모르면 조건주문 목록에 남아 있는지로 사람이 확인함.
 * 다시 눌러도 토스가 없는 조건주문으로 거부하므로 새 주문이 생기지 않음.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class CancelConditionalOrderService(
    private val conditionalOrders: ConditionalOrderPort,
    private val journal: ConditionalOrderJournal,
    private val ulids: UlidGenerator,
) : CancelConditionalOrderUseCase {
    override fun cancel(request: CancelConditionalOrderRequest): ConditionalCancelPlacement {
        // 토스 취소에는 멱등 키가 없어 이 키는 이벤트끼리 잇는 데만 씀
        val key = ClientOrderId.from(ulids.next())
        journal.recordEvent(
            request.userId,
            request.deviceId,
            ConditionalOrderCancelRequested(key, request.conditionalOrderId),
        )
        return try {
            conditionalOrders.cancelConditionalOrder(request.userId, request.conditionalOrderId)
            ConditionalCancelPlacement.Canceled
        } catch (e: OrderResultUnknownException) {
            journal.recordEvent(
                request.userId,
                request.deviceId,
                OrderResultUnknown(key, e.message.orEmpty()),
            )
            ConditionalCancelPlacement.Pending
        } catch (e: OrderRejectedException) {
            journal.recordEvent(
                request.userId,
                request.deviceId,
                OrderRejected(key, e.message.orEmpty()),
            )
            throw e
        } catch (e: BrokerUnavailableException) {
            journal.recordEvent(
                request.userId,
                request.deviceId,
                OrderRejected(key, e.message.orEmpty()),
            )
            throw e
        }
    }
}
