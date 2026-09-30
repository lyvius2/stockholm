package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.FeedState
import banghak.stock.core.domain.trading.FeedStatus
import banghak.stock.core.domain.trading.FeedTopic
import banghak.stock.core.domain.trading.OrderBook
import banghak.stock.core.domain.trading.OrderEvent
import banghak.stock.core.domain.trading.OrderEventType
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradeTick
import banghak.stock.core.port.FeedListener
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.treeToValue
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory

/**
 * 키 주인 한 명의 토스 웹소켓 연결.
 * 구독은 full-replace 라 연결·재연결·변경 때마다 원하는 구독 전체를 배열 하나로 보내고, 비면 `[]` 을 보냄.
 * 연결 성공과 구독 확정은 다름.
 * 선언 id 에 맞는 `subscriptions` 승인을 받아야 CONNECTED 를 알림.
 * 승인이 늦거나 선언 전체가 실패하면 끊고 다시 붙음(선언 오류 때 토스는 이전 구독을 그대로 둠).
 * 로그에는 프레임 원문을 남기지 않고 종류·코드만 남김.
 */
internal class TossFeedConnection(
    private val owner: UserId,
    private val client: OkHttpClient,
    private val url: String,
    private val tokens: TossTokenCache,
    private val accountSeqs: TossAccountCache,
    private val scheduler: ScheduledExecutorService,
    private val settings: TossFeedSettings,
    listeners: List<FeedListener>,
) {
    private val lock = ReentrantLock()
    private val requestIds = AtomicLong()
    private val inbox = TossFeedInbox(settings.orderEventCapacity, listeners, ::overflow)
    private var desired: Set<FeedTopic> = emptySet()
    private var declaredByKey: Map<String, FeedTopic> = emptyMap()
    private var pendingRequestId: String? = null
    private var socket: WebSocket? = null
    private var usedToken = ""
    private var failures = 0
    private var hasBeenAcknowledged = false
    private var isFirstAckAfterOpen = false
    private var isClosed = false
    private var pendingDeclare: ScheduledFuture<*>? = null
    private var pendingReconnect: ScheduledFuture<*>? = null
    private var pendingAckTimeout: ScheduledFuture<*>? = null

    fun declare(topics: Set<FeedTopic>) = lock.withLock {
        desired = topics
        when {
            isClosed -> Unit
            socket == null && pendingReconnect == null -> connect()
            socket != null -> scheduleDeclare(settings.declareDebounce)
        }
    }

    fun close() = lock.withLock {
        isClosed = true
        listOf(pendingDeclare, pendingReconnect, pendingAckTimeout).forEach { it?.cancel(false) }
        socket?.close(NORMAL_CLOSURE, "release")
        socket = null
        inbox.close()
    }

    private fun connect() {
        val token = runCatching {
            tokens.bearer(owner)
        }
            .getOrElse {
                log.warn("토스 실시간 연결용 토큰을 받지 못함({})", it::class.simpleName)
                scheduleReconnect(null)
                return
            }
        usedToken = token
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        socket = client.newWebSocket(request, Listener())
    }

    // 짧은 시간의 여러 변경을 한 번의 선언으로 묶음(선언 빈도 한도 초당 5회)
    private fun scheduleDeclare(delay: Duration) {
        pendingDeclare?.cancel(false)
        pendingDeclare =
            scheduler.schedule(
                { lock.withLock { sendDeclaration() } },
                delay.toMillis(),
                TimeUnit.MILLISECONDS,
            )
    }

    private fun sendDeclaration() {
        val target = socket ?: return
        if (desired.isEmpty()) {
            pendingRequestId = null
            declaredByKey = emptyMap()
            target.send(EMPTY_DECLARATION)
            if (isFirstAckAfterOpen) acknowledge(emptySet(), emptySet())
            return
        }
        val requestId = "req-${requestIds.incrementAndGet()}"
        declaredByKey = desired.associateBy(::keyOf)
        pendingRequestId = requestId
        target.send(mapper.writeValueAsString(declarationOf(requestId, desired)))
        armAckTimeout(requestId)
    }

    private fun declarationOf(requestId: String, topics: Set<FeedTopic>): List<Map<String, Any>> {
        val grouped =
            topics.groupBy(::typeOf).map { (type, members) ->
                mapOf("type" to type, "codes" to members.map(::codeOf).sorted())
            }
        return listOf(mapOf<String, Any>("id" to requestId)) + grouped
    }

    private fun armAckTimeout(requestId: String) {
        pendingAckTimeout?.cancel(false)
        pendingAckTimeout =
            scheduler.schedule(
                {
                    lock.withLock {
                        if (pendingRequestId == requestId) {
                            log.warn("토스 실시간 선언 승인이 오지 않아 다시 연결함")
                            socket?.cancel()
                        }
                    }
                },
                settings.ackTimeout.toMillis(),
                TimeUnit.MILLISECONDS,
            )
    }

    private fun handleSubscriptions(frame: JsonNode) = lock.withLock {
        val id = frame.path("id").asText()
        if (id.isEmpty() || id != pendingRequestId) return@withLock
        pendingRequestId = null
        pendingAckTimeout?.cancel(false)
        val accepted = frame.path("subscribed").mapNotNull { declaredByKey[it.asText()] }.toSet()
        val rejected =
            frame.path("rejected").mapNotNull { declaredByKey[it.path("target").asText()] }.toSet()
        frame.path("rejected").forEach {
            log.warn("토스 실시간 구독 거부: {} ({})", it.path("target").asText(), it.path("code").asText())
        }
        acknowledge(accepted, rejected)
    }

    private fun acknowledge(accepted: Set<FeedTopic>, rejected: Set<FeedTopic>) {
        val recovered = isFirstAckAfterOpen && hasBeenAcknowledged
        failures = 0
        hasBeenAcknowledged = true
        isFirstAckAfterOpen = false
        inbox.state(FeedState(owner, FeedStatus.CONNECTED, recovered, accepted, rejected))
    }

    // 선언 전체 실패 때 토스는 이전 구독을 유지하므로 원하는 구독과 달라짐.
    // 빈도 초과만 1초 뒤 다시 선언하고 나머지는 다시 연결함
    private fun handleError(frame: JsonNode) = lock.withLock {
        val code = frame.path("error").path("code").asText()
        log.warn("토스 실시간 선언 오류: {}", code)
        pendingRequestId = null
        pendingAckTimeout?.cancel(false)
        if (code == RATE_LIMITED) scheduleDeclare(RATE_LIMIT_BACKOFF) else socket?.cancel()
    }

    private fun overflow() = lock.withLock {
        log.warn("처리되지 않은 내 주문 이벤트가 상한을 넘어 연결을 다시 맺음")
        socket?.cancel()
    }

    // 401 이면 토큰을 갱신하고, 403(허용 IP)이면 접속 거부를 알린 뒤 가장 긴 간격으로만 다시 시도함
    private fun scheduleReconnect(status: Int?) {
        if (isClosed) return
        if (status == HTTP_UNAUTHORIZED)
            runCatching { tokens.renewAfterRejection(owner, usedToken) }
        val delay =
            if (status == HTTP_FORBIDDEN) settings.reconnectMax
            else
                settings.reconnectInitial
                    .multipliedBy(1L shl failures.coerceAtMost(MAX_BACKOFF_STEPS))
                    .coerceAtMost(settings.reconnectMax)
        failures++
        pendingReconnect =
            scheduler.schedule(
                {
                    lock.withLock {
                        pendingReconnect = null
                        if (!isClosed && socket == null) connect()
                    }
                },
                delay.toMillis(),
                TimeUnit.MILLISECONDS,
            )
    }

    private fun handle(text: String) {
        val frame = runCatching { mapper.readTree(text) }.getOrNull() ?: return
        when (frame.path("type").asText()) {
            "message" -> handleMessage(frame)
            "subscriptions" -> handleSubscriptions(frame)
            "error" -> handleError(frame)
        }
    }

    private fun handleMessage(frame: JsonNode) {
        val parts = frame.path("topic").asText().split(':')
        if (parts.size != 3) return
        val data = frame.path("data")
        when ("${parts[0]}:${parts[1]}") {
            "trade:kr",
            "trade:us" -> tradeOf(symbolOf(parts[1], parts[2]), data)?.let(inbox::trade)
            "orderbook:kr",
            "orderbook:us" -> orderBookOf(symbolOf(parts[1], parts[2]), data)?.let(inbox::orderBook)
            PERSONAL_ORDER -> orderEventOf(parts[2], data)?.let(inbox::order)
        }
    }

    // 응답 통화가 종목 통화와 다르면 잘못된 금액이 되므로 버림
    private fun tradeOf(symbol: Symbol, data: JsonNode): TradeTick? {
        if (data.path("currency").asText() != symbol.market.currency.name) return null
        return runCatching {
            TradeTick(
                symbol,
                Money.of(BigDecimal(data.path("price").asText()), symbol.market.currency),
                Quantity.of(BigDecimal(data.path("volume").asText())),
                TossOrderMapping.parse(data.path("timestamp").asText()),
            )
        }
            .getOrNull()
    }

    private fun orderBookOf(symbol: Symbol, data: JsonNode): OrderBook? {
        val currency = symbol.market.currency
        if (data.path("currency").asText() != currency.name) return null
        val levels = { node: JsonNode ->
            node.map {
                OrderBook.Level(
                    Money.of(BigDecimal(it.path("price").asText()), currency),
                    Quantity.of(BigDecimal(it.path("volume").asText())),
                )
            }
        }
        val stamp = data.path("timestamp").takeUnless { it.isNull || it.isMissingNode }?.asText()
        return runCatching {
            OrderBook(
                symbol,
                levels(data.path("asks")),
                levels(data.path("bids")),
                stamp?.let(TossOrderMapping::parse) ?: Instant.EPOCH,
            )
        }
            .getOrNull()
    }

    // 토픽의 계좌 순번과 본문의 계좌 순번이 모두 이 사용자의 선택 계좌와 같을 때만 그 사용자 주문으로 봄
    private fun orderEventOf(topicSeq: String, data: JsonNode): OrderEvent? {
        val expected = runCatching { accountSeqs.accountSeq(owner).toString() }.getOrNull()
        if (
            expected == null || topicSeq != expected || data.path("accountSeq").asText() != expected
        ) {
            log.warn("선택 계좌와 다른 주문 이벤트를 버림")
            return null
        }
        return runCatching {
            val order = mapper.treeToValue<TossOrder>(data.path("order"))
            OrderEvent(
                owner,
                eventTypeOf(data.path("event").asText()),
                TossOrderMapping.recordOf(order),
            )
        }
            .onFailure {
                // 이 이벤트는 다시 오지 않으므로 받는 쪽이 주문을 다시 조회해 맞추게 알림
                log.warn("토스 주문 이벤트를 해석하지 못함({}). 재동기를 요청함", it::class.simpleName)
                inbox.orderLost(owner)
            }
            .getOrNull()
    }

    private fun eventTypeOf(value: String): OrderEventType =
        OrderEventType.entries.firstOrNull { it.name == value } ?: OrderEventType.UNKNOWN

    private fun keyOf(topic: FeedTopic): String = "${typeOf(topic)}:${codeOf(topic)}"

    private fun typeOf(topic: FeedTopic): String =
        when (topic) {
            is FeedTopic.Trades -> "trade:${marketCode(topic.symbol.market)}"
            is FeedTopic.OrderBooks -> "orderbook:${marketCode(topic.symbol.market)}"
            is FeedTopic.MyOrders -> PERSONAL_ORDER
        }

    private fun codeOf(topic: FeedTopic): String =
        when (topic) {
            is FeedTopic.Trades -> topic.symbol.code
            is FeedTopic.OrderBooks -> topic.symbol.code
            is FeedTopic.MyOrders -> accountSeqs.accountSeq(topic.userId).toString()
        }

    private fun symbolOf(market: String, code: String): Symbol =
        Symbol(if (market == "kr") Market.KR else Market.US, code)

    private fun marketCode(market: Market): String = market.name.lowercase()

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            lock.withLock {
                isFirstAckAfterOpen = true
                sendDeclaration()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            dropped(webSocket, null)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            dropped(webSocket, response?.code)

        private fun dropped(webSocket: WebSocket, status: Int?) = lock.withLock {
            if (socket !== webSocket) return@withLock
            socket = null
            pendingRequestId = null
            pendingAckTimeout?.cancel(false)
            if (isClosed) return@withLock
            val feedStatus =
                if (status == HTTP_FORBIDDEN) FeedStatus.ACCESS_DENIED else FeedStatus.DISCONNECTED
            inbox.state(FeedState(owner, feedStatus, recovered = false))
            scheduleReconnect(status)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(TossFeedConnection::class.java)
        private val mapper = jsonMapper {
            addModule(kotlinModule())
            disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        }
        private const val PERSONAL_ORDER = "personal:order"
        private const val EMPTY_DECLARATION = "[]"
        private const val RATE_LIMITED = "rate-limit-exceeded"
        private val RATE_LIMIT_BACKOFF: Duration = Duration.ofSeconds(1)
        private const val NORMAL_CLOSURE = 1000
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val MAX_BACKOFF_STEPS = 6
    }
}
