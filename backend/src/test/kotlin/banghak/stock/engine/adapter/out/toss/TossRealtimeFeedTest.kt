package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.FeedState
import banghak.stock.core.domain.trading.FeedStatus
import banghak.stock.core.domain.trading.FeedTopic
import banghak.stock.core.domain.trading.OrderBook
import banghak.stock.core.domain.trading.OrderEvent
import banghak.stock.core.domain.trading.OrderEventType
import banghak.stock.core.domain.trading.OrderStatus
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradeTick
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.port.FeedListener
import banghak.stock.engine.config.ExternalEndpointProperties
import banghak.stock.engine.config.TossHttpConfig
import banghak.stock.shared.config.HttpProperties
import banghak.stock.shared.config.OkHttpConfig
import banghak.stock.shared.config.RetrofitFactory
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.MemorySecretStore
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import java.time.Duration
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 토스 웹소켓 어댑터를 실제 웹소켓 왕복(mockwebserver3)으로 검증함.
 * 토큰·계좌 조회는 WireMock.
 */
class TossRealtimeFeedTest {
    private val rest = WireMockServer(wireMockConfig().dynamicPort())
    private val ws = MockWebServer()
    private val user = TradingFixtures.user
    private val clock = MutableClock(Instant.parse("2026-09-30T01:00:00Z"))
    private val secrets = MemorySecretStore()
    private val events = LinkedBlockingQueue<Any>()
    private val mapper = ObjectMapper()
    private lateinit var feed: TossRealtimeFeed

    @BeforeEach
    fun setUp() {
        rest.start()
        ws.start()
        rest.stubFor(
            post(urlPathEqualTo("/oauth2/token"))
                .willReturn(
                    json("""{"access_token":"tok-1","token_type":"Bearer","expires_in":86400}""")
                )
        )
        rest.stubFor(
            get(urlPathEqualTo("/api/v1/accounts"))
                .willReturn(json("""{"result":[{"accountSeq":1,"accountType":"BROKERAGE"}]}"""))
        )
        secrets.put(
            SecretKey.user(user, CredentialKind.TOSS.secretName("CLIENT_ID")),
            SecretValue.of("client-id-1"),
        )
        secrets.put(
            SecretKey.user(user, CredentialKind.TOSS.secretName("CLIENT_SECRET")),
            SecretValue.of("secret-1"),
        )
        val shared = OkHttpConfig().okHttpClient(HttpProperties())
        val endpoints =
            ExternalEndpointProperties(
                tossBaseUrl = "http://127.0.0.1:${rest.port()}/",
                tossWebSocketUrl = ws.url("/ws/v1").toString(),
            )
        val config = TossHttpConfig(RetrofitFactory(shared), endpoints, shared)
        val tokens = TossTokenCache(TossTokenIssuer(config.tossAuthClient(), secrets, clock), clock)
        val settings =
            TossFeedSettings(
                pingInterval = Duration.ofSeconds(30),
                reconnectInitial = Duration.ofMillis(50),
                reconnectMax = Duration.ofMillis(200),
                declareDebounce = Duration.ofMillis(20),
                ackTimeout = Duration.ofMillis(500),
            )
        feed =
            TossRealtimeFeed(
                shared,
                endpoints,
                tokens,
                TossAccountCache(TossAccountLookup(config.tossAccountClient(tokens))),
                settings,
            )
        feed.addListener(
            object : FeedListener {
                override fun onTrade(tick: TradeTick) {
                    events += tick
                }

                override fun onOrderBook(book: OrderBook) {
                    events += book
                }

                override fun onOrderEvent(event: OrderEvent) {
                    events += event
                }

                override fun onState(state: FeedState) {
                    events += state
                }
            }
        )
    }

    @AfterEach
    fun tearDown() {
        feed.shutdown()
        ws.close()
        rest.stop()
    }

    @Test
    @DisplayName("Bearer 헤더로 붙고 원하는 구독 전체를 배열 하나로 선언하며, 내 주문은 계좌 순번으로 구독함")
    fun connectsAndDeclaresWholeSubscription() {
        val server = upgrade()

        feed.declare(
            user,
            setOf(
                FeedTopic.Trades(TradingFixtures.samsung),
                FeedTopic.OrderBooks(TradingFixtures.samsung),
                FeedTopic.MyOrders(user),
            ),
        )

        assertThat(ws.takeRequest(5, TimeUnit.SECONDS)?.headers?.get("Authorization"))
            .isEqualTo("Bearer tok-1")
        assertThat(acknowledge(server))
            .containsExactlyInAnyOrder("trade:kr:005930", "orderbook:kr:005930", "personal:order:1")
        val state = next() as FeedState
        assertThat(state.status).isEqualTo(FeedStatus.CONNECTED)
        assertThat(state.recovered).isFalse()
        assertThat(state.isOrderStreamLive(user)).isTrue()
    }

    @Test
    @DisplayName("체결·호가·내 주문 프레임을 도메인 값으로 바꿔 넘기고, 통화가 다른 체결은 버림")
    fun dispatchesFrames() {
        val server = upgrade()
        feed.declare(
            user,
            setOf(FeedTopic.Trades(TradingFixtures.samsung), FeedTopic.MyOrders(user)),
        )
        acknowledge(server)
        next()

        server.send(
            """{"type":"message","topic":"trade:kr:005930","data":{"price":"72000","volume":"12","timestamp":"2026-09-30T09:30:42.000+09:00","currency":"USD"}}"""
        )
        server.send(
            """{"type":"message","topic":"trade:kr:005930","data":{"price":"72000","volume":"12","timestamp":"2026-09-30T09:30:42.000+09:00","currency":"KRW"}}"""
        )
        server.send(
            """{"type":"message","topic":"orderbook:kr:005930","data":{"timestamp":"2026-09-30T09:30:00.000+09:00","currency":"KRW","asks":[{"price":"72100","volume":"5"}],"bids":[{"price":"72000","volume":"10"}]}}"""
        )
        server.send(orderFrame(topicSeq = "1", dataSeq = "1", orderId = "ORD-1"))

        val tick = next() as TradeTick
        assertThat(tick.price).isEqualTo(Money.of("72000", Currency.KRW))
        assertThat(tick.volume).isEqualTo(Quantity.of(12))
        val book = next() as OrderBook
        assertThat(book.asks.single().price).isEqualTo(Money.of("72100", Currency.KRW))
        val order = next() as OrderEvent
        assertThat(order.type).isEqualTo(OrderEventType.FILL)
        assertThat(order.userId).isEqualTo(user)
        assertThat(order.record.status).isEqualTo(OrderStatus.FILLED)
        assertThat(order.record.filledQuantity).isEqualTo(Quantity.of(10))
    }

    @Test
    @DisplayName("다시 선언하면 이전 구독을 통째로 바꿔 끼움")
    fun redeclarationReplacesSubscription() {
        val server = upgrade()
        feed.declare(user, setOf(FeedTopic.Trades(TradingFixtures.samsung)))
        acknowledge(server)

        feed.declare(user, setOf(FeedTopic.Trades(TradingFixtures.nvidia)))

        assertThat(declaredTopics(server.next())).containsExactly("trade:us:NVDA")
    }

    @Test
    @DisplayName("연결이 끊기면 알리고 다시 붙어 같은 구독을 재선언하며 복구됨으로 알림")
    fun reconnectsAndRedeclares() {
        val first = upgrade()
        val second = upgrade()
        feed.declare(user, setOf(FeedTopic.MyOrders(user)))
        acknowledge(first)
        assertThat((next() as FeedState).recovered).isFalse()

        first.socket().close(1001, "going away")

        assertThat(next()).isEqualTo(FeedState(user, FeedStatus.DISCONNECTED, recovered = false))
        assertThat(acknowledge(second)).containsExactly("personal:order:1")
        val state = next() as FeedState
        assertThat(state.status).isEqualTo(FeedStatus.CONNECTED)
        assertThat(state.recovered).isTrue()
    }

    @Test
    @DisplayName("다른 사용자의 주문은 구독할 수 없고, 연결당 구독은 100건까지임")
    fun validatesDeclarations() {
        val other = UserId.from(Ulid.of(clock.instant(), ByteArray(10) { 7 }))

        assertThatThrownBy { feed.declare(user, setOf(FeedTopic.MyOrders(other))) }
            .isInstanceOf(InvalidValueException::class.java)
        val tooMany =
            (0 until 101)
                .map {
                    FeedTopic.Trades(
                        banghak.stock.core.domain.market.Symbol(
                            banghak.stock.core.domain.market.Market.KR,
                            "%06d".format(it),
                        )
                    )
                }
                .toSet()
        assertThatThrownBy { feed.declare(user, tooMany) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("연결을 놓으면 닫고 다시 붙지 않음")
    fun releaseStopsReconnecting() {
        val server = upgrade()
        feed.declare(user, setOf(FeedTopic.Trades(TradingFixtures.samsung)))
        acknowledge(server)
        assertThat(ws.takeRequest(5, TimeUnit.SECONDS)).describedAs("처음 연결").isNotNull()

        feed.release(user)

        assertThat(server.closed.poll(5, TimeUnit.SECONDS)).isNotNull()
        assertThat(ws.takeRequest(500, TimeUnit.MILLISECONDS)).describedAs("재연결 요청 없음").isNull()
    }

    @Test
    @DisplayName("구독을 모두 비우면 id 요소 없이 빈 배열만 보냄")
    fun emptyDeclarationSendsBareArray() {
        val server = upgrade()
        feed.declare(user, setOf(FeedTopic.Trades(TradingFixtures.samsung)))
        acknowledge(server)

        feed.declare(user, emptySet())

        assertThat(server.next()).isEqualTo("[]")
    }

    @Test
    @DisplayName("선언 id 와 맞는 승인이 와야 CONNECTED 를 알리고, 거부된 내 주문은 살아 있는 스트림으로 보지 않음")
    fun announcesConnectedOnlyAfterMatchingAcknowledgement() {
        val server = upgrade()
        feed.declare(
            user,
            setOf(FeedTopic.Trades(TradingFixtures.samsung), FeedTopic.MyOrders(user)),
        )
        val id = mapper.readTree(server.next()).first().path("id").asText()

        server.send(
            ackFrame("req-other", subscribed = listOf("trade:kr:005930", "personal:order:1"))
        )
        assertThat(events.poll(300, TimeUnit.MILLISECONDS)).describedAs("다른 id 의 승인은 무시").isNull()

        server.send(
            ackFrame(
                id,
                subscribed = listOf("trade:kr:005930"),
                rejected = listOf("personal:order:1"),
            )
        )

        val state = next() as FeedState
        assertThat(state.status).isEqualTo(FeedStatus.CONNECTED)
        assertThat(state.accepted).containsExactly(FeedTopic.Trades(TradingFixtures.samsung))
        assertThat(state.rejected).containsExactly(FeedTopic.MyOrders(user))
        assertThat(state.isOrderStreamLive(user)).isFalse()
    }

    @Test
    @DisplayName("선언 전체가 실패하면 끊고 다시 붙어 재선언함")
    fun reconnectsWhenDeclarationFails() {
        val first = upgrade()
        val second = upgrade()
        feed.declare(user, setOf(FeedTopic.MyOrders(user)))
        val id = mapper.readTree(first.next()).first().path("id").asText()

        first.send(
            """{"type":"error","error":{"code":"internal-error","message":"x"},"id":"$id"}"""
        )

        assertThat(next()).isEqualTo(FeedState(user, FeedStatus.DISCONNECTED, recovered = false))
        assertThat(acknowledge(second)).containsExactly("personal:order:1")
        assertThat((next() as FeedState).isOrderStreamLive(user)).isTrue()
    }

    @Test
    @DisplayName("승인이 제한 시간 안에 오지 않으면 끊고 다시 붙음")
    fun reconnectsWhenAcknowledgementTimesOut() {
        val first = upgrade()
        val second = upgrade()
        feed.declare(user, setOf(FeedTopic.MyOrders(user)))
        first.next()

        assertThat(next()).isEqualTo(FeedState(user, FeedStatus.DISCONNECTED, recovered = false))
        assertThat(acknowledge(second)).containsExactly("personal:order:1")
        assertThat((next() as FeedState).status).isEqualTo(FeedStatus.CONNECTED)
    }

    @Test
    @DisplayName("선언 빈도 초과면 연결을 유지한 채 같은 구독을 다시 선언함")
    fun redeclaresAfterRateLimit() {
        val server = upgrade()
        feed.declare(user, setOf(FeedTopic.MyOrders(user)))
        val id = mapper.readTree(server.next()).first().path("id").asText()

        server.send(
            """{"type":"error","error":{"code":"rate-limit-exceeded","message":"x"},"id":"$id"}"""
        )

        assertThat(acknowledge(server)).containsExactly("personal:order:1")
        assertThat((next() as FeedState).status).isEqualTo(FeedStatus.CONNECTED)
    }

    @Test
    @DisplayName("토픽이나 본문의 계좌 순번이 선택 계좌와 다른 주문 이벤트는 버림")
    fun dropsOrderEventsOfOtherAccounts() {
        val server = upgrade()
        feed.declare(user, setOf(FeedTopic.MyOrders(user)))
        acknowledge(server)
        next()

        server.send(orderFrame(topicSeq = "2", dataSeq = "2", orderId = "ORD-OTHER-TOPIC"))
        server.send(orderFrame(topicSeq = "1", dataSeq = "2", orderId = "ORD-OTHER-DATA"))
        server.send(orderFrame(topicSeq = "1", dataSeq = "1", orderId = "ORD-MINE"))

        assertThat((next() as OrderEvent).record.brokerOrderId).isEqualTo("ORD-MINE")
        assertThat(events.poll(200, TimeUnit.MILLISECONDS)).isNull()
    }

    private fun upgrade(): ServerSide =
        ServerSide().also { ws.enqueue(MockResponse.Builder().webSocketUpgrade(it).build()) }

    private fun next(): Any = requireNotNull(events.poll(5, TimeUnit.SECONDS)) { "수신 이벤트 없음" }

    // 선언을 받아 전부 승인하고 선언한 토픽을 돌려줌
    private fun acknowledge(server: ServerSide): List<String> {
        val frame = server.next()
        val id = mapper.readTree(frame).first().path("id").asText()
        val topics = declaredTopics(frame)
        server.send(ackFrame(id, subscribed = topics))
        return topics
    }

    private fun ackFrame(
        id: String,
        subscribed: List<String>,
        rejected: List<String> = emptyList(),
    ): String =
        mapper.writeValueAsString(
            mapOf(
                "type" to "subscriptions",
                "id" to id,
                "subscribed" to subscribed,
                "rejected" to
                    rejected.map {
                        mapOf("target" to it, "code" to "account-not-found", "message" to "x")
                    },
            )
        )

    private fun orderFrame(topicSeq: String, dataSeq: String, orderId: String): String =
        """{"type":"message","topic":"personal:order:$topicSeq","data":{"event":"FILL","accountSeq":"$dataSeq","order":{"orderId":"$orderId","symbol":"005930","side":"BUY","orderType":"LIMIT","timeInForce":"DAY","status":"FILLED","price":"72000","quantity":"10","orderAmount":null,"currency":"KRW","orderedAt":"2026-09-30T09:30:00.000+09:00","canceledAt":null,"execution":{"filledQuantity":"10","averageFilledPrice":"72000","filledAmount":"720000","commission":"108","tax":"0","settlementDate":"2026-10-02"}}}}"""

    private fun declaredTopics(frame: String): List<String> =
        mapper
            .readTree(frame)
            .filter { it.has("type") }
            .flatMap { entry ->
                entry.path("codes").map { "${entry.path("type").asText()}:${it.asText()}" }
            }

    private fun json(body: String) =
        aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)

    /**
     * 서버 쪽 웹소켓.
     * 받은 프레임을 모으고 프레임을 보낼 수 있음.
     */
    private class ServerSide : WebSocketListener() {
        val received = LinkedBlockingQueue<String>()
        val opened = LinkedBlockingQueue<WebSocket>()
        val closed = LinkedBlockingQueue<Int>()
        private var ws: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            ws = webSocket
            opened += webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received += text
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closed += code
            webSocket.close(1000, null)
        }

        fun next(): String = requireNotNull(received.poll(5, TimeUnit.SECONDS)) { "선언 프레임 없음" }

        fun socket(): WebSocket = ws ?: requireNotNull(opened.poll(5, TimeUnit.SECONDS))

        fun send(text: String) {
            socket().send(text)
        }
    }
}
