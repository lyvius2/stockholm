package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import java.time.Instant

/**
 * 실시간 체결 틱.
 * 시세 채널은 유실될 수 있어 화면은 최신값만 씀.
 */
data class TradeTick(val symbol: Symbol, val price: Money, val volume: Quantity, val at: Instant)

/**
 * 내 주문 실시간 이벤트 종류.
 * 토스가 새 값을 더할 수 있어 모르는 값은 [UNKNOWN] 임.
 */
enum class OrderEventType {
    PENDING,
    PARTIAL_FILL,
    FILL,
    CANCELING,
    CANCELED,
    REPLACING,
    REPLACED,
    REJECTED,
    CANCEL_REJECTED,
    REPLACE_REJECTED,
    UNKNOWN,
}

/**
 * 내 주문 실시간 이벤트.
 * [record] 는 그 시점의 주문 상태임.
 */
data class OrderEvent(val userId: UserId, val type: OrderEventType, val record: BrokerOrderRecord)

/**
 * 실시간 구독 대상.
 * 체결·호가는 종목, 내 주문은 사용자 단위임.
 */
sealed interface FeedTopic {
    data class Trades(val symbol: Symbol) : FeedTopic

    data class OrderBooks(val symbol: Symbol) : FeedTopic

    data class MyOrders(val userId: UserId) : FeedTopic
}

/**
 * 실시간 연결 상태.
 * CONNECTED 는 연결만이 아니라 선언한 구독을 증권사가 승인한 뒤에만 옴.
 * [accepted]·[rejected] 는 그 승인의 결과이며, 내 주문 스트림이 살아 있는지는 [accepted] 에 그 사용자의 [FeedTopic.MyOrders] 가
 * 있는지로 봄.
 * [recovered] 가 true 면 끊겼다가 다시 승인된 것이라, 끊긴 동안의 내 주문 이벤트를 받으려면 미체결 주문을 다시 조회해야 함.
 */
data class FeedState(
    val owner: UserId,
    val status: FeedStatus,
    val recovered: Boolean,
    val accepted: Set<FeedTopic> = emptySet(),
    val rejected: Set<FeedTopic> = emptySet(),
) {
    init {
        if (
            status != FeedStatus.CONNECTED &&
                (recovered || accepted.isNotEmpty() || rejected.isNotEmpty())
        )
            throw InvalidValueException("복구·승인 결과는 CONNECTED 에만 담음: $status")
    }

    fun isOrderStreamLive(userId: UserId): Boolean =
        status == FeedStatus.CONNECTED && FeedTopic.MyOrders(userId) in accepted
}

enum class FeedStatus {
    CONNECTED,
    DISCONNECTED,
    ACCESS_DENIED,
}
