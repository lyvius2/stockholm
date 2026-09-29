package banghak.stock.engine.adapter.`in`.scheduler

import banghak.stock.core.usecase.ProcessFillsUseCase
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 체결 대기열을 몇 초마다 lot 에 반영함. */
@Component
@Profile(RuntimeProfiles.ENGINE)
class FillProcessingScheduler(private val processor: ProcessFillsUseCase) {
    @Scheduled(fixedDelayString = "\${stockholm.lots.fill-check-interval:PT5S}")
    fun processFills() = processor.processFills()
}
