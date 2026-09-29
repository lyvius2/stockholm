package banghak.stock.engine.adapter.`in`.scheduler

import banghak.stock.core.usecase.SyncOrderStreamsUseCase
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 토스 키가 생기거나 없어진 사용자의 주문 채널 구독을 맞추고, 못다 한 재동기를 다시 시도함. */
@Component
@Profile(RuntimeProfiles.ENGINE)
class OrderStreamScheduler(private val streams: SyncOrderStreamsUseCase) {
    @Scheduled(
        initialDelayString = "\${stockholm.orders.stream-initial-delay:PT15S}",
        fixedDelayString = "\${stockholm.orders.stream-check-interval:PT1M}",
    )
    fun syncOrderStreams() = streams.syncOrderStreams()
}
