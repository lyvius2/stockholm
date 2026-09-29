package banghak.stock.engine.application.market

import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.market.KrTradingDetail
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.StockFlags
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.port.StockCatalogPort
import banghak.stock.core.port.StockFlagsCachePort
import banghak.stock.core.usecase.LookupStockFlagsUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import java.time.Duration
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 종목 경고 플래그를 짧은 TTL 캐시로 돌려줌.
 * 거래정지는 장중에도 바뀌므로 마스터가 아니라 매번 종목 정보에서 새로 받음.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class StockFlagsService(
    private val catalog: StockCatalogPort,
    private val cache: StockFlagsCachePort,
    private val clock: Clock,
) : LookupStockFlagsUseCase {
    override fun flags(symbol: Symbol): StockFlags {
        val cached = cache.find(symbol)
        if (cached != null && isFresh(cached)) return cached
        val fetched =
            try {
                fetch(symbol)
            } catch (e: MarketDataUnavailableException) {
                return cached ?: throw e
            }
        cache.save(fetched)
        return fetched
    }

    private fun isFresh(flags: StockFlags): Boolean =
        Duration.between(flags.asOf, clock.instant()) < FLAGS_TTL

    private fun fetch(symbol: Symbol): StockFlags =
        StockFlags.of(symbol, catalog.warnings(symbol), krDetailOf(symbol), clock.instant())

    private fun krDetailOf(symbol: Symbol): KrTradingDetail? =
        if (symbol.market == Market.KR) catalog.profiles(listOf(symbol)).firstOrNull()?.krDetail
        else null

    companion object {
        // VI 는 토스에 수 초 안에 반영되므로 짧게 둠
        private val FLAGS_TTL: Duration = Duration.ofSeconds(10)
    }
}
