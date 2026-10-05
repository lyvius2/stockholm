package banghak.stock.engine.application.market

import banghak.stock.core.domain.market.StockQuery
import banghak.stock.core.domain.market.StockSummary
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.port.StockMasterPort
import banghak.stock.core.usecase.LookupStockUseCase
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 종목 마스터 조회·검색.
 * 상장폐지 종목은 결과에 없음.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class StockLookupService(private val master: StockMasterPort) : LookupStockUseCase {
    override fun find(symbol: Symbol): StockSummary? = master.find(symbol)

    override fun search(query: StockQuery): List<StockSummary> = master.search(query)
}
