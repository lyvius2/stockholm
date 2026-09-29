package banghak.stock.core.usecase

import banghak.stock.core.domain.market.StockFlags
import banghak.stock.core.domain.market.Symbol

/** 종목 경고 플래그 조회. */
interface LookupStockFlagsUseCase {
    /**
     * 짧은 캐시가 살아 있으면 캐시를, 아니면 증권사에서 새로 받은 값을 돌려줌.
     * 증권사에 닿지 않으면 지난 캐시를 돌려주므로 신선도는 [StockFlags.asOf] 로 판단할 것.
     *
     * @throws banghak.stock.core.domain.error.MarketDataUnavailableException 증권사에 닿지 않고 캐시도 없으면 발생함
     */
    fun flags(symbol: Symbol): StockFlags
}
