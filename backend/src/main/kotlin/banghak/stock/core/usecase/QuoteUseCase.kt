package banghak.stock.core.usecase

import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.OrderBook
import banghak.stock.core.domain.trading.Quote

/**
 * 현재가·호가 단건 조회.
 * 평소에는 스트림이 밀어 주고, 스트림이 끊겼을 때 화면이 폴링(가격 1초·호가 2초)으로 내려올 때 씀.
 */
interface LookupQuoteUseCase {
    /** @throws banghak.stock.core.domain.error.MarketDataUnavailableException 시세를 받지 못하면 발생함 */
    fun quote(symbol: Symbol): Quote

    /** @throws banghak.stock.core.domain.error.MarketDataUnavailableException 호가를 받지 못하면 발생함 */
    fun orderBook(symbol: Symbol): OrderBook
}
