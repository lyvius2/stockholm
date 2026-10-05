package banghak.stock.engine.adapter.`in`.ws

import banghak.stock.core.domain.market.IndexCode
import banghak.stock.core.domain.market.IndexEntry
import banghak.stock.core.domain.market.IndexQuote
import banghak.stock.core.domain.market.IndexSetChoice
import banghak.stock.core.domain.market.IndexSetState
import banghak.stock.core.domain.market.IndexSourceState
import banghak.stock.core.domain.market.IndexTicker
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.Candle
import banghak.stock.core.domain.trading.CandleInterval
import banghak.stock.core.domain.trading.OrderBook
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.Quote
import banghak.stock.core.domain.trading.StreamMessage
import banghak.stock.core.domain.trading.StreamViewerId
import banghak.stock.core.domain.trading.StreamWatch
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.usecase.MarketStreamUseCase
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SpecVersion
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import tools.jackson.databind.json.JsonMapper

/** 연결 하나의 구독 요청과 밀어 주는 메시지가 protocol 스키마에 맞음. */
class LocalStreamHandlerTest {
    private val watches = mutableListOf<StreamWatch>()
    private val left = mutableListOf<StreamViewerId>()
    private val stream =
        object : MarketStreamUseCase {
            override fun watch(watch: StreamWatch) {
                watches += watch
            }

            override fun leave(viewer: StreamViewerId) {
                left += viewer
            }

            override fun flush() {}

            override fun broadcast(message: StreamMessage) {}
        }
    private val sessions = LocalStreamSessions(JsonMapper.builder().build())
    private val handler = LocalStreamHandler(stream, sessions, JsonMapper.builder().build())
    private val session: WebSocketSession =
        mock(WebSocketSession::class.java).also {
            `when`(it.id).thenReturn("ws-1")
            `when`(it.isOpen).thenReturn(true)
        }

    @Test
    @DisplayName("subscribe 메시지의 종목 전체를 그 연결의 구독으로 넘기고, 끊기면 거둠")
    fun subscribesAndLeaves() {
        handler.afterConnectionEstablished(session)
        handler.handleMessage(
            session,
            TextMessage(
                """{"type":"subscribe","symbols":[{"market":"KR","code":"005930"},{"market":"US","code":"NVDA"}]}"""
            ),
        )
        handler.afterConnectionClosed(session, CloseStatus.NORMAL)

        assertThat(watches)
            .containsExactly(
                StreamWatch(
                    StreamViewerId("ws-1"),
                    setOf(TradingFixtures.samsung, TradingFixtures.nvidia),
                )
            )
        assertThat(left).containsExactly(StreamViewerId("ws-1"))
    }

    @Test
    @DisplayName("모르는 메시지나 잘못된 종목이 오면 연결을 닫음")
    fun closesOnBadRequest() {
        handler.afterConnectionEstablished(session)

        handler.handleMessage(
            session,
            TextMessage("""{"type":"subscribe","symbols":[{"market":"KR","code":"bad"}]}"""),
        )

        verify(session).close(CloseStatus.BAD_DATA)
        assertThat(watches).isEmpty()
    }

    @Test
    @DisplayName("미는 메시지는 묶음 배열이며 각 항목이 stream-server-message 스키마를 통과함")
    fun pushedMessagesConformToSchema() {
        handler.afterConnectionEstablished(session)
        val nvidia = TradingFixtures.nvidia
        val usd = { amount: String -> Money.of(amount, Currency.USD) }
        val at = Instant.parse("2026-09-30T14:30:05Z")

        sessions.push(
            StreamViewerId("ws-1"),
            listOf(
                StreamMessage.QuoteUpdate(Quote(nvidia, usd("120.50"), at)),
                StreamMessage.OrderBookUpdate(
                    OrderBook(
                        nvidia,
                        listOf(OrderBook.Level(usd("120.51"), Quantity.of("1.5"))),
                        listOf(OrderBook.Level(usd("120.49"), Quantity.of(10))),
                        at,
                    )
                ),
                StreamMessage.LiveCandleUpdate(
                    Candle(
                        nvidia,
                        CandleInterval.MINUTE_1,
                        at,
                        usd("120"),
                        usd("121"),
                        usd("119"),
                        usd("120.50"),
                        Quantity.of(7),
                    )
                ),
                StreamMessage.MarketFeedState(true),
                StreamMessage.IndexTickerUpdate(
                    IndexTicker(
                        IndexSetChoice(Market.US, IndexSetState.OPEN),
                        listOf(
                            IndexEntry(
                                IndexCode.DJIA,
                                IndexQuote.of(
                                    IndexCode.DJIA,
                                    BigDecimal("42000"),
                                    null,
                                    at,
                                    true,
                                    null,
                                    "FRED",
                                ),
                                IndexSourceState.FRESH,
                            ),
                            IndexEntry(IndexCode.NASDAQ, null, IndexSourceState.UNCONFIGURED),
                            IndexEntry(
                                IndexCode.SP500,
                                IndexQuote.of(
                                    IndexCode.SP500,
                                    BigDecimal("600.00"),
                                    BigDecimal("590.00"),
                                    at,
                                    false,
                                    "SPY",
                                    "TOSS_ETF",
                                ),
                                IndexSourceState.FRESH,
                            ),
                        ),
                        at,
                    )
                ),
            ),
        )

        val sent = ArgumentCaptor.forClass(TextMessage::class.java)
        verify(session, timeout(2000)).sendMessage(sent.capture())
        val items = ObjectMapper().readTree(sent.value.payload)
        assertThat(items.isArray).isTrue()
        assertThat(items.map { it.path("type").asText() })
            .containsExactly("quote", "orderBook", "liveCandle", "feedState", "indexTicker")
        val entries = items[4].path("indexTicker").path("entries")
        assertThat(entries[2].path("quote").path("proxy").asText()).isEqualTo("SPY")
        assertThat(entries[0].path("quote").path("change").isNull).isTrue()
        assertThat(entries[1].path("state").asText()).isEqualTo("UNCONFIGURED")
        assertThat(entries[1].path("quote").isNull).isTrue()
        assertThat(items[0].path("quote").path("last").path("amount").asText()).isEqualTo("120.50")
        assertThat(items[2].path("liveCandle").path("volume").asText()).isEqualTo("7")
        val schema = schema()
        items.forEach { assertThat(schema.validate(it)).describedAs(it.toString()).isEmpty() }
    }

    @Test
    @DisplayName("모르는 연결에는 보내지 않음")
    fun ignoresUnknownViewer() {
        sessions.push(StreamViewerId("nobody"), listOf(StreamMessage.MarketFeedState(false)))

        verify(session, never()).sendMessage(any())
    }

    @Test
    @DisplayName("쓰기가 막힌 느린 연결은 미는 쪽을 붙잡지 않고, 묶음이 8개 넘게 밀리면 끊음")
    fun slowSessionIsDroppedWithoutBlockingPush() {
        val blocked = CountDownLatch(1)
        `when`(session.sendMessage(any())).thenAnswer {
            blocked.await()
            null
        }
        handler.afterConnectionEstablished(session)
        val message = listOf(StreamMessage.MarketFeedState(true))

        val started = System.nanoTime()
        repeat(9) { sessions.push(StreamViewerId("ws-1"), message) }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertThat(elapsedMillis).isLessThan(500)
        verify(session, timeout(2000)).close(CloseStatus.SERVICE_OVERLOAD)
        blocked.countDown()
    }

    private fun schema() =
        JsonSchemaFactory.builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
            .schemaMappers {
                it.mapPrefix(SCHEMA_ID_PREFIX, SCHEMAS_DIR.toAbsolutePath().toUri().toString())
            }
            .build()
            .getSchema(
                SchemaLocation.of(
                    SCHEMAS_DIR.resolve("stream/stream-server-message.schema.json")
                        .toUri()
                        .toString()
                )
            )

    companion object {
        private const val SCHEMA_ID_PREFIX = "https://stockholm.banghak/protocol/"
        private val SCHEMAS_DIR: Path = Path.of("..", "protocol", "schemas")
    }
}
