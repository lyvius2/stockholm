package banghak.stock.core.port

import banghak.stock.core.domain.market.IndexQuote

/**
 * 지수 티커의 마지막 값(`market_index_quote`).
 * 이력은 두지 않고 지수마다 한 행이며, 데몬이 다시 떠도 마지막 값을 보이게 함.
 */
interface IndexQuoteStorePort {
    fun loadAll(): List<IndexQuote>

    fun saveAll(quotes: List<IndexQuote>)
}
