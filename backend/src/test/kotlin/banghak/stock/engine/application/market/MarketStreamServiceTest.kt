package banghak.stock.engine.application.market

import banghak.stock.core.domain.account.Installation
import banghak.stock.core.domain.account.SetupState
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.Candle
import banghak.stock.core.domain.trading.CandleInterval
import banghak.stock.core.domain.trading.FeedState
import banghak.stock.core.domain.trading.FeedStatus
import banghak.stock.core.domain.trading.FeedTopic
import banghak.stock.core.domain.trading.OrderBook
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.StreamMessage
import banghak.stock.core.domain.trading.StreamViewerId
import banghak.stock.core.domain.trading.StreamWatch
import banghak.stock.core.domain.trading.TradeTick
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.usecase.LookupLiveCandleUseCase
import banghak.stock.support.fakes.FakeRealtimeFeed
import banghak.stock.support.fakes.FakeStreamPush
import banghak.stock.support.fakes.MemoryInstallationPort
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class MarketStreamServiceTest {
    private val admin = TradingFixtures.user
    private val member = UserId.from(Ulid.of(TradingFixtures.now, ByteArray(10) { 7 }))
    private val samsung = TradingFixtures.samsung
    private val hynix = Symbol(Market.KR, "000660")
    private val feed = FakeRealtimeFeed()
    private val push = FakeStreamPush()
    private val liveCandle = mutableMapOf<Symbol, Candle>()
    private var liveCandleHook: (() -> Unit)? = null
    private val installations =
        MemoryInstallationPort().apply {
            installation =
                Installation(
                    "i_1",
                    SetupState.COMPLETE,
                    admin,
                    null,
                    null,
                    TradingFixtures.now,
                    TradingFixtures.now,
                )
        }
    private val service =
        MarketStreamService(
                feed,
                FeedSubscriptionService(feed),
                object : LookupLiveCandleUseCase {
                    override fun liveCandle(symbol: Symbol): Candle? {
                        liveCandleHook?.invoke()
                        return this@MarketStreamServiceTest.liveCandle[symbol]
                    }
                },
                installations,
                push,
            )
            .also { it.listen() }
    private val viewerA = StreamViewerId("ws-a")
    private val viewerB = StreamViewerId("ws-b")

    @Test
    @DisplayName("화면들이 보는 종목의 합집합을 admin 연결에 체결·호가 토픽으로 선언하고, 연결이 끊기면 거둠")
    fun declaresUnionOnAdminConnection() {
        service.watch(StreamWatch(viewerA, setOf(samsung)))
        service.watch(StreamWatch(viewerB, setOf(samsung, hynix)))

        assertThat(feed.declared[admin])
            .containsExactlyInAnyOrder(
                FeedTopic.Trades(samsung),
                FeedTopic.OrderBooks(samsung),
                FeedTopic.Trades(hynix),
                FeedTopic.OrderBooks(hynix),
            )

        service.leave(viewerB)
        assertThat(feed.declared[admin])
            .containsExactlyInAnyOrder(FeedTopic.Trades(samsung), FeedTopic.OrderBooks(samsung))

        service.leave(viewerA)
        assertThat(feed.declared).doesNotContainKey(admin)
        assertThat(feed.released).contains(admin)
    }

    @Test
    @DisplayName("밀 때는 종목별 최신 체결·호가·진행 봉만 그 종목을 보는 화면에만 보내고, 보낼 것이 없으면 조용함")
    fun flushesLatestPerSymbolToWatchers() {
        service.watch(StreamWatch(viewerA, setOf(samsung)))
        service.watch(StreamWatch(viewerB, setOf(hynix)))
        push.pushed.clear()
        service.onTrade(tick(samsung, "70000"))
        service.onTrade(tick(samsung, "70100"))
        service.onOrderBook(OrderBook(samsung, emptyList(), emptyList(), TradingFixtures.now))
        liveCandle[samsung] = candle(samsung)

        service.flush()
        service.flush()

        val messages = push.messagesTo(viewerA)
        assertThat(messages).hasSize(3)
        assertThat((messages[0] as StreamMessage.QuoteUpdate).quote.last).isEqualTo(krw("70100"))
        assertThat(messages[1]).isInstanceOf(StreamMessage.OrderBookUpdate::class.java)
        assertThat(messages[2]).isInstanceOf(StreamMessage.LiveCandleUpdate::class.java)
        assertThat(push.messagesTo(viewerB)).isEmpty()
    }

    @Test
    @DisplayName("연결하면 지금 시세 연결 상태를 바로 알리고, admin 연결 상태가 올 때마다 모든 화면에 알림. 다른 연결 상태는 무시")
    fun announcesMarketFeedState() {
        service.watch(StreamWatch(viewerA, setOf(samsung)))
        assertThat(push.messagesTo(viewerA)).containsExactly(StreamMessage.MarketFeedState(false))

        service.onState(
            FeedState(member, FeedStatus.CONNECTED, false, setOf(FeedTopic.MyOrders(member)))
        )
        service.onState(
            FeedState(
                admin,
                FeedStatus.CONNECTED,
                false,
                setOf(FeedTopic.Trades(samsung), FeedTopic.OrderBooks(samsung)),
            )
        )
        service.onState(FeedState(admin, FeedStatus.DISCONNECTED, false))

        assertThat(push.messagesTo(viewerA))
            .containsExactly(
                StreamMessage.MarketFeedState(false),
                StreamMessage.MarketFeedState(true),
                StreamMessage.MarketFeedState(false),
            )
    }

    @Test
    @DisplayName("연결됐어도 증권사가 거절했거나 구독 한도 밖인 종목은 그 화면에 시세 없음으로 알림")
    fun reportsUnavailableSymbols() {
        service.watch(StreamWatch(viewerA, setOf(samsung, hynix)))
        push.pushed.clear()

        // 삼성전자만 승인, 하이닉스는 거절(모르는 종목 등)
        service.onState(
            FeedState(
                admin,
                FeedStatus.CONNECTED,
                false,
                accepted = setOf(FeedTopic.Trades(samsung), FeedTopic.OrderBooks(samsung)),
                rejected = setOf(FeedTopic.Trades(hynix), FeedTopic.OrderBooks(hynix)),
            )
        )

        assertThat(push.messagesTo(viewerA))
            .containsExactly(StreamMessage.MarketFeedState(true, setOf(hynix)))

        // 한도 밖 종목은 선언조차 되지 않아 승인 목록에 없음
        val beyond = (0 until 50).map { krSymbol(it) }
        service.watch(StreamWatch(viewerB, beyond.take(20).toSet()))
        service.watch(StreamWatch(StreamViewerId("ws-c"), beyond.drop(20).take(20).toSet()))
        push.pushed.clear()
        service.watch(StreamWatch(StreamViewerId("ws-d"), beyond.drop(40).toSet()))

        val lastState =
            push.messagesTo(StreamViewerId("ws-d")).single() as StreamMessage.MarketFeedState
        assertThat(lastState.unavailableSymbols).contains(krSymbol(49))
    }

    @Test
    @DisplayName("밀고 있는 동안 들어온 체결은 잃지 않고 다음 묶음에 최신값으로 감")
    fun tickDuringFlushIsNotLost() {
        service.watch(StreamWatch(viewerA, setOf(samsung)))
        push.pushed.clear()
        service.onTrade(tick(samsung, "70000"))
        // 최신값을 읽는 순간(진행 봉 조회) 새 체결이 들어오는 상황
        liveCandleHook = { service.onTrade(tick(samsung, "70100")) }

        service.flush()
        liveCandleHook = null
        service.flush()

        val quotes = push.messagesTo(viewerA).filterIsInstance<StreamMessage.QuoteUpdate>()
        assertThat(quotes.map { it.quote.last }).containsExactly(krw("70000"), krw("70100"))
    }

    @Test
    @DisplayName("한 화면은 20종목까지이고, 전체가 구독 한도를 넘으면 먼저 선언한 화면의 종목부터 49개만 받음")
    fun capsSymbols() {
        assertThatThrownBy { StreamWatch(viewerA, (0 until 21).map { krSymbol(it) }.toSet()) }
            .isInstanceOf(InvalidValueException::class.java)

        service.watch(StreamWatch(viewerA, (0 until 20).map { krSymbol(it) }.toSet()))
        service.watch(StreamWatch(viewerB, (20 until 40).map { krSymbol(it) }.toSet()))
        service.watch(
            StreamWatch(StreamViewerId("ws-c"), (40 until 60).map { krSymbol(it) }.toSet())
        )

        val trades = feed.declared.getValue(admin).filterIsInstance<FeedTopic.Trades>()
        assertThat(trades).hasSize(49)
        assertThat(trades.map { it.symbol }).contains(krSymbol(0), krSymbol(48))
        assertThat(trades.map { it.symbol }).doesNotContain(krSymbol(49))
    }

    private fun krSymbol(index: Int) = Symbol(Market.KR, index.toString().padStart(6, '0'))

    private fun tick(symbol: Symbol, price: String) =
        TradeTick(symbol, krw(price), Quantity.of(1), Instant.parse("2026-09-30T00:00:10Z"))

    private fun candle(symbol: Symbol): Candle {
        val price = krw("70000")
        return Candle(
            symbol,
            CandleInterval.MINUTE_1,
            Instant.parse("2026-09-30T00:00:00Z"),
            price,
            price,
            price,
            price,
            Quantity.of(1),
        )
    }
}
