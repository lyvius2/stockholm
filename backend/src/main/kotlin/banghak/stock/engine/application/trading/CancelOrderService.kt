package banghak.stock.engine.application.trading

import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.OrderRejectedException
import banghak.stock.core.domain.error.OrderResultUnknownException
import banghak.stock.core.domain.eventlog.OrderCancelRequested
import banghak.stock.core.domain.eventlog.OrderRejected
import banghak.stock.core.domain.eventlog.OrderResultUnknown
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.port.TradingPort
import banghak.stock.core.usecase.CancelOrderRequest
import banghak.stock.core.usecase.CancelOrderUseCase
import banghak.stock.core.usecase.CancelPlacement
import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.crypto.UlidGenerator
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 미체결 취소.
 * 원주문 상태는 증권사에서 그 자리에서 받아 판단함.
 * 결과를 모르면 실시간 주문 채널·재동기가 원주문 상태를 알려 주므로 따로 확인하지 않음.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class CancelOrderService(
    private val trading: TradingPort,
    private val journal: OrderJournal,
    private val ulids: UlidGenerator,
) : CancelOrderUseCase {
    override fun cancel(request: CancelOrderRequest): CancelPlacement {
        val original = trading.lookupOrder(request.userId, request.brokerOrderId)
        if (!original.status.isChangeable)
            throw InvalidValueException("취소할 수 없는 상태임: ${original.status}")
        // 취소는 토스에 멱등 키가 없어 이 키는 이벤트끼리 잇는 데만 씀
        val key = ClientOrderId.from(ulids.next())
        journal.recordEvent(
            request.userId,
            request.deviceId,
            OrderCancelRequested(key, original.brokerOrderId),
        )
        return try {
            CancelPlacement.Requested(
                trading.cancelOrder(request.userId, original.brokerOrderId).brokerOrderId
            )
        } catch (e: OrderResultUnknownException) {
            journal.recordEvent(
                request.userId,
                request.deviceId,
                OrderResultUnknown(key, e.message.orEmpty()),
            )
            CancelPlacement.Pending
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
