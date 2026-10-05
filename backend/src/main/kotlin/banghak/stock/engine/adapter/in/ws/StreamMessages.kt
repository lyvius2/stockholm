package banghak.stock.engine.adapter.`in`.ws

import banghak.stock.core.domain.market.IndexQuote
import banghak.stock.core.domain.market.IndexTicker
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.Candle
import banghak.stock.core.domain.trading.OrderBook
import banghak.stock.core.domain.trading.Quote
import banghak.stock.core.domain.trading.StreamMessage
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * 로컬 WebSocket 메시지.
 * 모양은 `protocol/schemas/stream` 의 JSON Schema 가 원본이며 화면 타입은 거기서 생성함.
 */
data class StreamClientMessage(val type: String = "", val symbols: List<SymbolDto> = emptyList())

data class SymbolDto(val market: String = "", val code: String = "") {
    fun toSymbol(): Symbol = Symbol(banghak.stock.core.domain.market.Market.valueOf(market), code)

    companion object {
        fun of(symbol: Symbol) = SymbolDto(symbol.market.name, symbol.code)
    }
}

data class MoneyDto(val amount: String, val currency: String) {
    companion object {
        fun of(money: Money) = MoneyDto(money.amount.toPlainString(), money.currency.name)
    }
}

data class QuoteDto(val last: MoneyDto, val asOf: Instant)

data class LevelDto(val price: MoneyDto, val quantity: String)

data class OrderBookDto(val asks: List<LevelDto>, val bids: List<LevelDto>, val asOf: Instant)

data class CandleDto(
    val openTime: Instant,
    val open: MoneyDto,
    val high: MoneyDto,
    val low: MoneyDto,
    val close: MoneyDto,
    val volume: String,
)

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
)

data class IndexEntryDto(
    val code: String,
    val name: String,
    val state: String,
    val quote: IndexQuoteDto?,
)

data class IndexTickerDto(
    val market: String,
    val state: String,
    val entries: List<IndexEntryDto>,
    val asOf: Instant,
    @get:JsonProperty("isDelayed") val isDelayed: Boolean,
)

data class FeedStateDto(
    @get:JsonProperty("isLive") val isLive: Boolean,
    val unavailableSymbols: List<SymbolDto>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class StreamServerMessage(
    val type: String,
    val symbol: SymbolDto? = null,
    val quote: QuoteDto? = null,
    val orderBook: OrderBookDto? = null,
    val liveCandle: CandleDto? = null,
    val feedState: FeedStateDto? = null,
    val indexTicker: IndexTickerDto? = null,
) {
    companion object {
        fun of(message: StreamMessage): StreamServerMessage =
            when (message) {
                is StreamMessage.QuoteUpdate -> quote(message.quote)
                is StreamMessage.OrderBookUpdate -> orderBook(message.orderBook)
                is StreamMessage.LiveCandleUpdate -> liveCandle(message.candle)
                is StreamMessage.IndexTickerUpdate -> indexTicker(message.ticker)
                is StreamMessage.MarketFeedState ->
                    StreamServerMessage(
                        "feedState",
                        feedState =
                            FeedStateDto(
                                message.isLive,
                                message.unavailableSymbols.map(SymbolDto::of),
                            ),
                    )
            }

        private fun quote(quote: Quote) =
            StreamServerMessage(
                "quote",
                SymbolDto.of(quote.symbol),
                quote = QuoteDto(MoneyDto.of(quote.last), quote.asOf),
            )

        private fun orderBook(book: OrderBook) =
            StreamServerMessage(
                "orderBook",
                SymbolDto.of(book.symbol),
                orderBook =
                    OrderBookDto(book.asks.map(::levelOf), book.bids.map(::levelOf), book.asOf),
            )

        private fun liveCandle(candle: Candle) =
            StreamServerMessage(
                "liveCandle",
                SymbolDto.of(candle.symbol),
                liveCandle =
                    CandleDto(
                        candle.openTime,
                        MoneyDto.of(candle.open),
                        MoneyDto.of(candle.high),
                        MoneyDto.of(candle.low),
                        MoneyDto.of(candle.close),
                        candle.volume.toString(),
                    ),
            )

        private fun indexTicker(ticker: IndexTicker) =
            StreamServerMessage(
                "indexTicker",
                indexTicker =
                    IndexTickerDto(
                        ticker.choice.market.name,
                        ticker.choice.state.name,
                        ticker.entries.map { entry ->
                            IndexEntryDto(
                                entry.code.name,
                                entry.code.displayName,
                                entry.state.name,
                                entry.quote?.let { quoteDto(it) },
                            )
                        },
                        ticker.asOf,
                        ticker.isDelayed,
                    ),
            )

        private fun quoteDto(quote: IndexQuote) =
            IndexQuoteDto(
                quote.code.name,
                quote.code.displayName,
                quote.value.toPlainString(),
                quote.change?.toPlainString(),
                quote.changeRatio?.ratio?.toPlainString(),
                quote.asOf,
                quote.isClosed,
                quote.proxy,
                quote.source,
            )

        private fun levelOf(level: OrderBook.Level) =
            LevelDto(MoneyDto.of(level.price), level.quantity.toString())
    }
}
