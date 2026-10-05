package banghak.stock.engine.application.market

import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.FeedState
import banghak.stock.core.domain.trading.FeedStatus
import banghak.stock.core.domain.trading.FeedTopic
import banghak.stock.core.domain.trading.OrderBook
import banghak.stock.core.domain.trading.Quote
import banghak.stock.core.domain.trading.StreamMessage
import banghak.stock.core.domain.trading.StreamViewerId
import banghak.stock.core.domain.trading.StreamWatch
import banghak.stock.core.domain.trading.TradeTick
import banghak.stock.core.port.FeedListener
import banghak.stock.core.port.InstallationPort
import banghak.stock.core.port.RealtimeFeedPort
import banghak.stock.core.port.StreamPushPort
import banghak.stock.core.usecase.FeedDemand
import banghak.stock.core.usecase.FeedSubscriptionUseCase
import banghak.stock.core.usecase.LookupLiveCandleUseCase
import banghak.stock.core.usecase.MarketStreamUseCase
import banghak.stock.shared.config.RuntimeProfiles
import jakarta.annotation.PostConstruct
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 화면 연결들이 보는 종목을 합쳐 증권사에 구독하고, 받은 시세를 종목별 최신값으로 모았다가 묶어 밈.
 * 시세는 유실 허용 채널이라 중간값은 버리고 최신값만 보냄.
 * 공용 시세는 admin 연결로 받으며, 연결당 구독 한도 안에서 먼저 선언한 화면의 종목이 우선임.
 * 한도 밖이거나 증권사가 거절한 종목은 그 화면에 연결 상태 메시지로 알림.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class MarketStreamService(
    private val feed: RealtimeFeedPort,
    private val subscriptions: FeedSubscriptionUseCase,
    private val liveCandles: LookupLiveCandleUseCase,
    private val installations: InstallationPort,
    private val push: StreamPushPort,
) : FeedListener, MarketStreamUseCase {
    private val watches = LinkedHashMap<StreamViewerId, Set<Symbol>>()
    private val watchLock = ReentrantLock()
    private val latestQuotes = ConcurrentHashMap<Symbol, Quote>()
    private val latestBooks = ConcurrentHashMap<Symbol, OrderBook>()
    private val changed = ConcurrentHashMap.newKeySet<Symbol>()

    // 증권사에 실제로 선언한 종목(한도 안)과 admin 시세 연결의 마지막 상태
    @Volatile private var declared: Set<Symbol> = emptySet()
    @Volatile private var marketFeed: FeedState? = null

    @PostConstruct fun listen() = feed.addListener(this)

    override fun watch(watch: StreamWatch) {
        watchLock.withLock {
            watches[watch.viewer] = watch.symbols
            declare()
        }
        push.push(watch.viewer, listOf(feedStateOf(watch.symbols)))
    }

    override fun leave(viewer: StreamViewerId) = watchLock.withLock {
        if (watches.remove(viewer) != null) declare()
    }

    // 최신값을 먼저 적고 바뀜 표시를 남김.
    // flush 는 표시를 지운 뒤 최신값을 읽으므로 그 사이에 온 값도 이번이나 다음 묶음에 반드시 들어감
    override fun onTrade(tick: TradeTick) {
        latestQuotes[tick.symbol] = Quote(tick.symbol, tick.price, tick.at)
        changed += tick.symbol
    }

    override fun onOrderBook(book: OrderBook) {
        latestBooks[book.symbol] = book
        changed += book.symbol
    }

    // 공용 시세 연결(admin)의 상태만 화면에 알림.
    // 승인 결과는 선언 때마다 바뀌므로 매번 모든 화면에 다시 알림
    override fun onState(state: FeedState) {
        if (state.owner != adminUserId()) return
        marketFeed = state
        watchLock
            .withLock { watches.toMap() }
            .forEach { (viewer, symbols) ->
                push.push(viewer, listOf(feedStateOf(symbols)))
            }
    }

    override fun broadcast(message: StreamMessage) {
        watchLock.withLock { watches.keys.toList() }.forEach { push.push(it, listOf(message)) }
    }

    override fun flush() {
        val updates = linkedMapOf<Symbol, List<StreamMessage>>()
        for (symbol in changed) {
            // 표시를 지운 다음 읽어야 읽은 뒤에 온 값의 표시가 남음
            if (changed.remove(symbol)) updates[symbol] = updatesOf(symbol)
        }
        if (updates.isEmpty()) return
        watchLock
            .withLock { watches.toMap() }
            .forEach { (viewer, watched) ->
                val messages = updates.filterKeys { it in watched }.values.flatten()
                if (messages.isNotEmpty()) push.push(viewer, messages)
            }
    }

    private fun updatesOf(symbol: Symbol): List<StreamMessage> =
        listOfNotNull(
            latestQuotes[symbol]?.let { StreamMessage.QuoteUpdate(it) },
            latestBooks[symbol]?.let { StreamMessage.OrderBookUpdate(it) },
            liveCandles.liveCandle(symbol)?.let { StreamMessage.LiveCandleUpdate(it) },
        )

    // 연결됐어도 한도 밖이거나 체결 토픽이 승인되지 않은 종목은 시세가 오지 않음
    private fun feedStateOf(symbols: Set<Symbol>): StreamMessage.MarketFeedState {
        val state = marketFeed
        val isLive = state?.status == FeedStatus.CONNECTED
        if (!isLive) return StreamMessage.MarketFeedState(isLive = false)
        val accepted = state.accepted
        val unavailable =
            symbols.filterTo(linkedSetOf()) {
                it !in declared || FeedTopic.Trades(it) !in accepted
            }
        return StreamMessage.MarketFeedState(isLive = true, unavailableSymbols = unavailable)
    }

    // 종목마다 체결·호가 두 토픽이고 admin 연결은 본인 주문 토픽도 쓰므로 그만큼 남김
    private fun declare() {
        val admin = adminUserId() ?: return
        val wanted = watches.values.flatten().distinct()
        val symbols = wanted.take(MAX_STREAM_SYMBOLS)
        if (symbols.size < wanted.size)
            log.warn("화면이 보는 종목이 구독 한도를 넘어 먼저 선언한 {}개만 받음", MAX_STREAM_SYMBOLS)
        declared = symbols.toSet()
        val topics = symbols.flatMap { listOf(FeedTopic.Trades(it), FeedTopic.OrderBooks(it)) }
        if (topics.isEmpty()) subscriptions.release(admin, FeedDemand.MARKET_STREAM)
        else subscriptions.require(admin, FeedDemand.MARKET_STREAM, topics.toSet())
    }

    private fun adminUserId() = installations.load()?.adminUserId

    companion object {
        private val log = LoggerFactory.getLogger(MarketStreamService::class.java)

        private const val TOPICS_PER_SYMBOL = 2
        private const val RESERVED_FOR_MY_ORDERS = 1
        private val MAX_STREAM_SYMBOLS =
            (RealtimeFeedPort.MAX_TOPICS - RESERVED_FOR_MY_ORDERS) / TOPICS_PER_SYMBOL
    }
}
