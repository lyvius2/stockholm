package banghak.stock.engine.adapter.`in`.ws

import banghak.stock.core.domain.trading.Candle
import banghak.stock.core.domain.trading.OrderBook
import banghak.stock.core.domain.trading.Quote
import banghak.stock.core.domain.trading.StreamMessage
import banghak.stock.engine.adapter.`in`.web.common.IndexTickerDto
import banghak.stock.engine.adapter.`in`.web.common.MoneyDto
import banghak.stock.engine.adapter.`in`.web.common.SymbolDto
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * 로컬 WebSocket 메시지.
 * 모양은 `protocol/schemas/stream` 의 JSON Schema 가 원본이며 화면 타입은 거기서 생성함.
 */
data class StreamClientMessage(val type: String = "", val symbols: List<SymbolDto> = emptyList())

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
                is StreamMessage.IndexTickerUpdate ->
                    StreamServerMessage(
                        "indexTicker",
                        indexTicker = IndexTickerDto.of(message.ticker),
                    )
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

        private fun levelOf(level: OrderBook.Level) =
            LevelDto(MoneyDto.of(level.price), level.quantity.toString())
    }
}
