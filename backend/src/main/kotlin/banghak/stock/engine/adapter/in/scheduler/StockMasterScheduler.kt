package banghak.stock.engine.adapter.`in`.scheduler

import banghak.stock.core.usecase.SyncStockMasterUseCase
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 종목 마스터가 오래됐는지 자주 확인함.
 * 확인은 DB 한 번이라 가볍고, 마법사를 마친 직후에도 곧 첫 동기화가 돎.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class StockMasterScheduler(private val sync: SyncStockMasterUseCase) {
    @Scheduled(
        initialDelayString = "\${stockholm.stock-master.initial-delay:PT20S}",
        fixedDelayString = "\${stockholm.stock-master.check-interval:PT1M}",
    )
    fun refreshIfStale() = sync.syncIfStale()
}
