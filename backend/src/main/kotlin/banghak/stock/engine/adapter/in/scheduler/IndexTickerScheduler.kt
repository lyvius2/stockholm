package banghak.stock.engine.adapter.`in`.scheduler

import banghak.stock.core.usecase.RefreshIndexTickerUseCase
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 지수 티커를 5분마다 새로 받음(설정으로 늘릴 수만 있음). */
@Component
@Profile(RuntimeProfiles.ENGINE)
class IndexTickerScheduler(private val ticker: RefreshIndexTickerUseCase) {
    @Scheduled(
        initialDelayString = "\${stockholm.index-ticker.initial-delay:PT45S}",
        fixedDelayString = "\${stockholm.index-ticker.interval:PT5M}",
    )
    fun refresh() = ticker.refresh()
}
