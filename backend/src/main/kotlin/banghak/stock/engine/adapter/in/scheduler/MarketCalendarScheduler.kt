package banghak.stock.engine.adapter.`in`.scheduler

import banghak.stock.core.usecase.SyncMarketCalendarUseCase
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 장 달력을 기동 직후와 매일 한 번 미리 받아 둠.
 * 처음에는 90일치를 받느라 달력 호출 한도(초당 3회)로 1분쯤 걸리지만 그 뒤에는 하루 몇 건임.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class MarketCalendarScheduler(private val calendar: SyncMarketCalendarUseCase) {
    @Scheduled(
        initialDelayString = "\${stockholm.calendar.initial-delay:PT40S}",
        fixedDelayString = "\${stockholm.calendar.sync-interval:PT24H}",
    )
    fun syncRecent() = calendar.syncRecent()
}
