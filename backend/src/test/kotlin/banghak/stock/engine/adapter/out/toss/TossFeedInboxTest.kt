package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.trading.BrokerOrderRecord
import banghak.stock.core.domain.trading.FeedState
import banghak.stock.core.domain.trading.OrderBook
import banghak.stock.core.domain.trading.OrderEvent
import banghak.stock.core.domain.trading.OrderEventType
import banghak.stock.core.domain.trading.OrderKind
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.OrderStatus
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TimeInForce
import banghak.stock.core.domain.trading.TradeTick
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.port.FeedListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 수신자가 막혔을 때 대기열이 쌓이지 않는지 검증함. */
class TossFeedInboxTest {
    private val delivered = CopyOnWriteArrayList<Any>()
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val overflows = AtomicInteger()
    private lateinit var inbox: TossFeedInbox

    @AfterEach
    fun tearDown() {
        release.countDown()
        inbox.close()
    }

    @Test
    @DisplayName("수신자가 막힌 동안 들어온 같은 종목 체결은 최신값 하나로 합쳐짐")
    fun conflatesMarketDataPerSymbol() {
        inbox =
            TossFeedInbox(ORDER_CAPACITY, listOf(BlockingListener()), overflows::incrementAndGet)
        inbox.trade(tick("72000"))
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()

        inbox.trade(tick("72100"))
        inbox.trade(tick("72200"))
        release.countDown()

        awaitDelivered(2)
        assertThat(delivered.map { (it as TradeTick).price.amount.toPlainString() })
            .containsExactly("72000", "72200")
    }

    @Test
    @DisplayName("밀린 주문 이벤트가 상한을 넘으면 넘친 것을 알리고, 상한 안의 이벤트는 순서대로 넘김")
    fun reportsOverflowWhenOrderEventsPileUp() {
        inbox =
            TossFeedInbox(ORDER_CAPACITY, listOf(BlockingListener()), overflows::incrementAndGet)
        inbox.order(event("ORD-1"))
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()

        inbox.order(event("ORD-2"))
        inbox.order(event("ORD-3"))
        inbox.order(event("ORD-4"))

        assertThat(overflows.get()).isEqualTo(1)
        release.countDown()
        awaitDelivered(3)
        assertThat(delivered.map { (it as OrderEvent).record.brokerOrderId })
            .containsExactly("ORD-1", "ORD-2", "ORD-3")
    }

    private inner class BlockingListener : FeedListener {
        override fun onTrade(tick: TradeTick) = block(tick)

        override fun onOrderBook(book: OrderBook) = block(book)

        override fun onOrderEvent(event: OrderEvent) = block(event)

        override fun onState(state: FeedState) = block(state)

        private fun block(value: Any) {
            delivered += value
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
    }

    private fun awaitDelivered(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (delivered.size < count && System.nanoTime() < deadline) Thread.sleep(10)
        assertThat(delivered).hasSize(count)
    }

    private fun tick(price: String) =
        TradeTick(
            TradingFixtures.samsung,
            TradingFixtures.krw(price),
            Quantity.of(1),
            TradingFixtures.now,
        )

    private fun event(orderId: String) =
        OrderEvent(
            TradingFixtures.user,
            OrderEventType.PENDING,
            BrokerOrderRecord(
                brokerOrderId = orderId,
                symbol = TradingFixtures.samsung,
                side = OrderSide.BUY,
                kind = OrderKind.LIMIT,
                timeInForce = TimeInForce.DAY,
                limitPrice = TradingFixtures.krw("72000"),
                quantity = Quantity.of(1),
                orderAmount = null,
                status = OrderStatus.PENDING,
                filledQuantity = Quantity.of(0),
                averageFilledPrice = null,
                filledAmount = null,
                fee = null,
                tax = null,
                orderedAt = TradingFixtures.now,
                filledAt = null,
                canceledAt = null,
            ),
        )

    companion object {
        private const val ORDER_CAPACITY = 2
    }
}
