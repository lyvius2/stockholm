package banghak.stock.engine.application.market

import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.OrderBook
import banghak.stock.core.domain.trading.Quote
import banghak.stock.core.port.MarketDataPort
import banghak.stock.core.usecase.LookupQuoteUseCase
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 현재가·호가 단건.
 * 공용 시세(admin 키)이며 캐시 없이 증권사에 바로 물음(폴링 간격은 화면이 지킴).
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class QuoteService(private val marketData: MarketDataPort) : LookupQuoteUseCase {
    override fun quote(symbol: Symbol): Quote =
        marketData.quotes(listOf(symbol)).firstOrNull { it.symbol == symbol }
            ?: throw MarketDataUnavailableException("$symbol 현재가를 받지 못함")

    override fun orderBook(symbol: Symbol): OrderBook = marketData.orderBook(symbol)
}
