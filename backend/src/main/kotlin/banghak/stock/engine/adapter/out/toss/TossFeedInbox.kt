package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.FeedState
import banghak.stock.core.domain.trading.OrderBook
import banghak.stock.core.domain.trading.OrderEvent
import banghak.stock.core.domain.trading.TradeTick
import banghak.stock.core.port.FeedListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory

/**
 * 연결 하나의 수신 대기열.
 * 읽기 스레드는 넣기만 하고, 가상 스레드 하나가 순서대로 수신자에게 넘김.
 * 시세는 유실 허용 채널이라 종목별 최신값으로 합쳐 쌓이지 않게 함.
 * 내 주문 이벤트는 순서를 지켜 모두 넘기되, 밀린 개수가 상한을 넘으면 [onOverflow] 로 알려 연결을 끊게 함(재연결 뒤 재동기).
 * 상태 알림은 드물고 잃으면 안 되므로 상한 없이 넘김.
 */
internal class TossFeedInbox(
    private val orderCapacity: Int,
    private val listeners: List<FeedListener>,
    private val onOverflow: () -> Unit,
) {
    private sealed interface Work {
        data object Market : Work

        data class Order(val event: OrderEvent) : Work

        data class State(val state: FeedState) : Work
    }

    private val queue = LinkedBlockingQueue<Work>()
    private val pendingOrders = AtomicInteger()
    private val latestTrades = ConcurrentHashMap<Symbol, TradeTick>()
    private val latestBooks = ConcurrentHashMap<Symbol, OrderBook>()
    private val isMarketQueued = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(true)
    private val worker: Thread = Thread.ofVirtual().name("toss-feed-inbox").start(::drainLoop)

    fun trade(tick: TradeTick) {
        latestTrades[tick.symbol] = tick
        signalMarket()
    }

    fun orderBook(book: OrderBook) {
        latestBooks[book.symbol] = book
        signalMarket()
    }

    fun order(event: OrderEvent) {
        if (pendingOrders.incrementAndGet() > orderCapacity) {
            pendingOrders.decrementAndGet()
            onOverflow()
            return
        }
        queue.put(Work.Order(event))
    }

    fun state(state: FeedState) = queue.put(Work.State(state))

    fun close() {
        isRunning.set(false)
        worker.interrupt()
    }

    private fun signalMarket() {
        if (isMarketQueued.compareAndSet(false, true)) queue.put(Work.Market)
    }

    private fun drainLoop() {
        while (isRunning.get()) {
            val work =
                try {
                    queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
                } catch (e: InterruptedException) {
                    return
                }
            deliver(work)
        }
    }

    private fun deliver(work: Work) {
        when (work) {
            Work.Market -> {
                isMarketQueued.set(false)
                drain(latestTrades) { listener, tick -> listener.onTrade(tick) }
                drain(latestBooks) { listener, book -> listener.onOrderBook(book) }
            }
            is Work.Order -> {
                pendingOrders.decrementAndGet()
                notifyAll { it.onOrderEvent(work.event) }
            }
            is Work.State -> notifyAll { it.onState(work.state) }
        }
    }

    private fun <T : Any> drain(
        latest: ConcurrentHashMap<Symbol, T>,
        call: (FeedListener, T) -> Unit,
    ) {
        for (symbol in latest.keys) {
            val value = latest.remove(symbol) ?: continue
            notifyAll { call(it, value) }
        }
    }

    private fun notifyAll(call: (FeedListener) -> Unit) {
        listeners.forEach { listener ->
            runCatching { call(listener) }
                .onFailure { log.warn("실시간 수신자 처리 실패({})", it::class.simpleName) }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(TossFeedInbox::class.java)
        private const val POLL_MILLIS = 200L
    }
}
