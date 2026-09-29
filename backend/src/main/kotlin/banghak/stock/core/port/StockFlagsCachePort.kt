package banghak.stock.core.port

import banghak.stock.core.domain.market.StockFlags
import banghak.stock.core.domain.market.Symbol

/**
 * 종목 경고 플래그의 짧은 캐시.
 * 현재값만 두고 이력은 두지 않음.
 */
interface StockFlagsCachePort {
    fun find(symbol: Symbol): StockFlags?

    fun save(flags: StockFlags)
}
