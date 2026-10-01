package banghak.stock.engine.adapter.`in`.scheduler

import banghak.stock.core.usecase.MarketStreamUseCase
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 모아 둔 시세를 초당 4회 화면에 밈. */
@Component
@Profile(RuntimeProfiles.ENGINE)
class MarketStreamScheduler(private val stream: MarketStreamUseCase) {
    @Scheduled(fixedRateString = "\${stockholm.stream.flush-interval:PT0.25S}")
    fun flush() = stream.flush()
}
