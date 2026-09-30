package banghak.stock.core.port

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.FeedState
import banghak.stock.core.domain.trading.FeedTopic
import banghak.stock.core.domain.trading.OrderBook
import banghak.stock.core.domain.trading.OrderEvent
import banghak.stock.core.domain.trading.TradeTick

/**
 * 실시간 체결·호가·내 주문 포트.
 * 구독은 선언형임: [declare] 는 그 키 주인([owner])의 구독 전체를 바꿔 끼움(빠진 대상은 해제).
 * 공용 시세는 admin 을, 내 주문은 그 사용자를 주인으로 선언함.
 * 연결당 대상은 [MAX_TOPICS] 개까지.
 * 시세는 유실될 수 있고, 내 주문은 연결 안에서만 빠짐없이 옴.
 * 재연결 뒤에는 [FeedListener.onState] 로 알림.
 */
interface RealtimeFeedPort {
    fun declare(owner: UserId, topics: Set<FeedTopic>)

    /** 그 주인의 연결을 닫음(로그아웃·키 삭제 때). */
    fun release(owner: UserId)

    fun addListener(listener: FeedListener)

    companion object {
        const val MAX_TOPICS = 100
    }
}

/**
 * 실시간 수신자.
 * 호출은 주인별로 순서가 지켜지며 읽기 스레드가 아닌 별도 실행기에서 옴.
 */
interface FeedListener {
    fun onTrade(tick: TradeTick) {}

    fun onOrderBook(book: OrderBook) {}

    fun onOrderEvent(event: OrderEvent) {}

    /**
     * [owner] 의 주문 이벤트 하나를 받았으나 읽지 못해 넘기지 못함.
     * 연결이 살아 있어도 그 이벤트는 다시 오지 않으므로, 받는 쪽은 증권사 주문을 다시 조회해 맞춰야 함.
     */
    fun onOrderEventLost(owner: UserId) {}

    fun onState(state: FeedState) {}
}
