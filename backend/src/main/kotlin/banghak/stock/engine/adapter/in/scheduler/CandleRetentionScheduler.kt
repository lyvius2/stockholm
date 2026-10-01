package banghak.stock.engine.adapter.`in`.scheduler

import banghak.stock.core.usecase.PurgeCandlesUseCase
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 보존 기간이 지난 1분봉을 하루 한 번 지움.
 * 두 시장이 모두 한산한 한국 시간 07:30 에 돎.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class CandleRetentionScheduler(private val candles: PurgeCandlesUseCase) {
    @Scheduled(cron = "\${stockholm.candles.purge-cron:0 30 7 * * *}", zone = "Asia/Seoul")
    fun purgeExpired() = candles.purgeExpired()
}
