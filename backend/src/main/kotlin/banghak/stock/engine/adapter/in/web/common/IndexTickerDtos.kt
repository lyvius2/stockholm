package banghak.stock.engine.adapter.`in`.web.common

import banghak.stock.core.domain.market.IndexEntry
import banghak.stock.core.domain.market.IndexQuote
import banghak.stock.core.domain.market.IndexTicker
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * 상단 바 지수 티커.
 * 스트림(`indexTicker`)과 REST(`GET /market/index-ticker`)가 같은 모양을 씀.
 */
data class IndexTickerDto(
    val market: String,
    val state: String,
    val entries: List<IndexEntryDto>,
    val asOf: Instant,
    @get:JsonProperty("isDelayed") val isDelayed: Boolean,
) {
    companion object {
        fun of(ticker: IndexTicker) =
            IndexTickerDto(
                ticker.choice.market.name,
                ticker.choice.state.name,
                ticker.entries.map(IndexEntryDto::of),
                ticker.asOf,
                ticker.isDelayed,
            )
    }
}

data class IndexEntryDto(
    val code: String,
    val name: String,
    val state: String,
    val quote: IndexQuoteDto?,
) {
    companion object {
        fun of(entry: IndexEntry) =
            IndexEntryDto(
                entry.code.name,
                entry.code.displayName,
                entry.state.name,
                entry.quote?.let(IndexQuoteDto::of),
            )
    }
}

data class IndexQuoteDto(
    val code: String,
    val name: String,
    val value: String,
    val change: String?,
    val changeRatio: String?,
    val asOf: Instant,
    @get:JsonProperty("isClosed") val isClosed: Boolean,
    val proxy: String?,
    val source: String,
) {
    companion object {
        fun of(quote: IndexQuote) =
            IndexQuoteDto(
                quote.code.name,
                quote.code.displayName,
                quote.value.toPlainString(),
                quote.change?.toPlainString(),
                quote.changeRatio?.toPlainString(),
                quote.asOf,
                quote.isClosed,
                quote.proxy,
                quote.source,
            )
    }
}
