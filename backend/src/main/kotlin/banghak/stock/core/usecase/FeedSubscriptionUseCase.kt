package banghak.stock.core.usecase

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.FeedTopic

/** 실시간 구독을 원하는 기능. */
enum class FeedDemand {
    /** 사용자 본인의 주문 상태(`personal:order`). */
    MY_ORDERS
}

/**
 * 실시간 구독 모음.
 * 토스 구독은 키 주인별로 전체를 바꿔 끼우는 방식이라, 여러 기능이 원하는 대상을 한곳에서 합쳐 선언함.
 * 기능마다 따로 선언하면 서로의 구독을 지움.
 */
interface FeedSubscriptionUseCase {
    /** [demand] 가 원하는 [owner] 연결의 구독 대상을 바꿈. */
    fun require(owner: UserId, demand: FeedDemand, topics: Set<FeedTopic>)

    /**
     * [demand] 의 구독을 거둠.
     * 그 주인에게 남은 구독이 없으면 연결을 닫음.
     */
    fun release(owner: UserId, demand: FeedDemand)
}

/**
 * 주문 실시간 반영의 구독 맞추기.
 * 토스 키가 있는 사용자마다 본인 주문 채널을 구독하고, 못다 한 미체결 재동기를 다시 시도함.
 */
interface SyncOrderStreamsUseCase {
    fun syncOrderStreams()
}
