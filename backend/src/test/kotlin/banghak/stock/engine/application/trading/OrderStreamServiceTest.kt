package banghak.stock.engine.application.trading

import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.CredentialMeta
import banghak.stock.core.domain.account.CredentialStatus
import banghak.stock.core.domain.account.Device
import banghak.stock.core.domain.account.Installation
import banghak.stock.core.domain.account.SetupState
import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.eventlog.OrderStatusChanged
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.FeedState
import banghak.stock.core.domain.trading.FeedStatus
import banghak.stock.core.domain.trading.FeedTopic
import banghak.stock.core.domain.trading.OrderEvent
import banghak.stock.core.domain.trading.OrderEventType
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderStatus
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.engine.application.market.FeedSubscriptionService
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.FakeRealtimeFeed
import banghak.stock.support.fakes.FakeTradingPort
import banghak.stock.support.fakes.MemoryBrokerOrderStore
import banghak.stock.support.fakes.MemoryCredentialMetaPort
import banghak.stock.support.fakes.MemoryDevicePort
import banghak.stock.support.fakes.MemoryEventStore
import banghak.stock.support.fakes.MemoryFillQueue
import banghak.stock.support.fakes.MemoryInstallationPort
import banghak.stock.support.fakes.MemorySubmissionStore
import banghak.stock.support.fakes.MemoryUserAccountPort
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.dao.OptimisticLockingFailureException

class OrderStreamServiceTest {
    private val clock = MutableClock(TradingFixtures.now)
    private val user = TradingFixtures.user
    private val feed = FakeRealtimeFeed()
    private val trading = FakeTradingPort()
    private val events = MemoryEventStore()
    private val orders = MemoryBrokerOrderStore()
    private val installations = MemoryInstallationPort()
    private val users = MemoryUserAccountPort()
    private val credentials = MemoryCredentialMetaPort()
    private val devices = MemoryDevicePort()
    private val fills = MemoryFillQueue()
    private val service =
        OrderStreamService(
            feed,
            FeedSubscriptionService(feed),
            trading,
            OrderJournal(events, MemorySubmissionStore(), orders, fills, clock),
            orders,
            installations,
            users,
            credentials,
            devices,
            clock,
        )
    private val live =
        FeedState(
            user,
            FeedStatus.CONNECTED,
            recovered = false,
            accepted = setOf(FeedTopic.MyOrders(user)),
        )

    @BeforeEach
    fun setUp() {
        installations.installation =
            Installation(
                "i_1",
                SetupState.COMPLETE,
                user,
                null,
                null,
                TradingFixtures.now,
                TradingFixtures.now,
            )
        users.save(TradingFixtures.account())
        credentials.upsert(tossKey(user, CredentialStatus.VERIFIED))
        devices.save(
            Device(TradingFixtures.device, "pk", "this-mac", null, TradingFixtures.now, null)
        )
        service.listen()
    }

    @Nested
    @DisplayName("주문 이벤트")
    inner class Events {
        @Test
        @DisplayName("우리 주문의 상태가 바뀌면 반영하고 상태 변경을 이벤트로 남김")
        fun appliesStatusChange() {
            orders.recordAccepted(TradingFixtures.brokerOrder(brokerOrderId = "B-1"), false)

            service.onOrderEvent(event(record("B-1", OrderStatus.FILLED, filled = 10)))

            assertThat(orders.findProgress(user, "B-1")?.status).isEqualTo(OrderStatus.FILLED)
            assertThat(statusChanges())
                .containsExactly(OrderStatusChanged("B-1", OrderStatus.PENDING, OrderStatus.FILLED))
        }

        @Test
        @DisplayName("처음 보는 주문(토스 앱에서 낸 주문)은 기록하되 상태 변경 이벤트는 남기지 않음")
        fun recordsExternalOrder() {
            service.onOrderEvent(event(record("APP-1", OrderStatus.PENDING, filled = 0)))

            assertThat(orders.findProgress(user, "APP-1")?.status).isEqualTo(OrderStatus.PENDING)
            assertThat(statusChanges()).isEmpty()
        }

        @Test
        @DisplayName("체결 수량이 늘면 늘어난 몫을 우리 주문의 출처와 함께 체결 대기열에 넣음")
        fun enqueuesFillIncrement() {
            orders.recordAccepted(TradingFixtures.brokerOrder(brokerOrderId = "B-1"), false)
            val partial =
                record("B-1", OrderStatus.PARTIALLY_FILLED, filled = 4)
                    .copy(filledAmount = TradingFixtures.krw("280000"))
            val filled =
                record("B-1", OrderStatus.FILLED, filled = 10)
                    .copy(filledAmount = TradingFixtures.krw("700000"))

            service.onOrderEvent(event(partial))
            service.onOrderEvent(event(filled))

            assertThat(fills.items.map { it.fill.quantity })
                .containsExactly(Quantity.of(4), Quantity.of(6))
            assertThat(fills.items.last().fill.amount).isEqualTo(TradingFixtures.krw("420000"))
            assertThat(fills.items.map { it.fill.orderOrigin }.distinct())
                .containsExactly(OrderOrigin.MANUAL)
        }

        @Test
        @DisplayName("체결 금액이 늦게 오면 그때까지 기다렸다가, 금액이 채워진 기록에서 넣지 못한 수량 전체를 넣음")
        fun enqueuesWhenAmountArrivesLater() {
            orders.recordAccepted(TradingFixtures.brokerOrder(brokerOrderId = "B-1"), false)
            val withoutAmount = record("B-1", OrderStatus.PARTIALLY_FILLED, filled = 4)
            val amountLater =
                record("B-1", OrderStatus.PARTIALLY_FILLED, filled = 4)
                    .copy(filledAmount = TradingFixtures.krw("280000"))
            val moreFilled =
                record("B-1", OrderStatus.FILLED, filled = 10)
                    .copy(filledAmount = TradingFixtures.krw("703000"))

            service.onOrderEvent(event(withoutAmount))
            assertThat(fills.items).isEmpty()
            service.onOrderEvent(event(amountLater))
            service.onOrderEvent(event(moreFilled))

            assertThat(fills.items.map { it.fill.quantity to it.fill.amount })
                .containsExactly(
                    Quantity.of(4) to TradingFixtures.krw("280000"),
                    Quantity.of(6) to TradingFixtures.krw("423000"),
                )
        }

        @Test
        @DisplayName("밖에서 낸 주문은 상태가 바뀌어도 상태 변경 이벤트를 남기지 않음")
        fun externalOrdersLeaveNoStatusEvents() {
            service.onOrderEvent(event(record("APP-1", OrderStatus.PENDING, filled = 0)))

            service.onOrderEvent(event(record("APP-1", OrderStatus.FILLED, filled = 10)))

            assertThat(orders.findProgress(user, "APP-1")?.status).isEqualTo(OrderStatus.FILLED)
            assertThat(statusChanges()).isEmpty()
        }

        @Test
        @DisplayName("반영이 실패하면 이벤트를 버리지 않고 재동기로 되찾음")
        fun failedEventIsRecoveredByResync() {
            orders.recordAccepted(TradingFixtures.brokerOrder(brokerOrderId = "B-1"), false)
            orders.applyFailures += IllegalStateException("DB 잠김")
            val filled = record("B-1", OrderStatus.FILLED, filled = 10)

            service.onOrderEvent(event(filled))
            assertThat(orders.findProgress(user, "B-1")?.status).isEqualTo(OrderStatus.PENDING)
            trading.closedOrders += filled
            service.syncOrderStreams()

            assertThat(orders.findProgress(user, "B-1")?.status).isEqualTo(OrderStatus.FILLED)
        }

        @Test
        @DisplayName("읽지 못한 이벤트가 있었다고 알림이 오면 연결이 살아 있어도 다음 맞추기 때 주문을 다시 받아 반영함")
        fun lostEventIsRecoveredByResync() {
            orders.recordAccepted(TradingFixtures.brokerOrder(brokerOrderId = "B-1"), false)
            trading.closedOrders += record("B-1", OrderStatus.FILLED, filled = 10)

            service.onOrderEventLost(user)
            assertThat(orders.findProgress(user, "B-1")?.status).isEqualTo(OrderStatus.PENDING)
            service.syncOrderStreams()

            assertThat(orders.findProgress(user, "B-1")?.status).isEqualTo(OrderStatus.FILLED)
        }

        @Test
        @DisplayName("같은 주문을 다른 쪽이 먼저 고쳐 버전이 어긋나면 다시 읽어 반영함")
        fun retriesOnVersionConflict() {
            orders.applyFailures += OptimisticLockingFailureException("버전 충돌")

            service.onOrderEvent(event(record("B-1", OrderStatus.FILLED, filled = 10)))

            assertThat(orders.findProgress(user, "B-1")?.status).isEqualTo(OrderStatus.FILLED)
        }

        @Test
        @DisplayName("이미 반영된 것보다 뒤처진 기록은 버림")
        fun ignoresStaleRecord() {
            service.onOrderEvent(event(record("B-1", OrderStatus.FILLED, filled = 10)))

            service.onOrderEvent(event(record("B-1", OrderStatus.PARTIALLY_FILLED, filled = 4)))

            assertThat(orders.findProgress(user, "B-1")?.status).isEqualTo(OrderStatus.FILLED)
        }
    }

    @Nested
    @DisplayName("재동기")
    inner class Resync {
        @Test
        @DisplayName("스트림이 살아나면 미체결을 받아 반영하고, 그 사이 닫힌 로컬 미체결은 상세로 최종 상태를 받음")
        fun reconcilesWhenStreamBecomesLive() {
            orders.recordAccepted(TradingFixtures.brokerOrder(brokerOrderId = "B-1"), false)
            trading.openOrders += record("APP-2", OrderStatus.PENDING, filled = 0)
            trading.details["B-1"] = record("B-1", OrderStatus.FILLED, filled = 10)

            service.onState(live)

            assertThat(orders.findProgress(user, "APP-2")?.status).isEqualTo(OrderStatus.PENDING)
            assertThat(orders.findProgress(user, "B-1")?.status).isEqualTo(OrderStatus.FILLED)
        }

        @Test
        @DisplayName("끊긴 사이 토스 앱에서 내고 다 체결된 주문도 종료 주문 목록으로 찾아 기록함")
        fun findsOrdersOpenedAndClosedWhileDisconnected() {
            trading.closedOrders += record("APP-9", OrderStatus.FILLED, filled = 10)

            service.onState(live)

            assertThat(orders.findProgress(user, "APP-9")?.status).isEqualTo(OrderStatus.FILLED)
        }

        @Test
        @DisplayName("종료 주문은 처음엔 최근 30일을, 다음부터는 마지막으로 끝까지 읽은 날부터 읽음")
        fun closedOrdersResumeFromLastSyncedDay() {
            val today = LocalDate.of(2026, 9, 28)

            service.onState(live)
            service.onState(FeedState(user, FeedStatus.DISCONNECTED, recovered = false))
            service.onState(live.copy(recovered = true))

            assertThat(trading.closedQueries.map { it.from }.distinct())
                .containsExactly(today.minusDays(30), today)
        }

        @Test
        @DisplayName("구독을 바꿔 다시 승인된 것은 끊긴 구간이 없어 맞추지 않고, 끊겼다 살아나면 다시 맞춤")
        fun reconcilesOnlyOnLiveTransition() {
            service.onState(live)
            val readsAfterFirst = trading.orderListReads

            service.onState(live)
            assertThat(trading.orderListReads).isEqualTo(readsAfterFirst)

            service.onState(FeedState(user, FeedStatus.DISCONNECTED, recovered = false))
            service.onState(live.copy(recovered = true))
            assertThat(trading.orderListReads).isGreaterThan(readsAfterFirst)
        }

        @Test
        @DisplayName("재동기가 실패하면 다음 구독 맞추기 때 다시 함")
        fun retriesFailedReconcile() {
            trading.accountFailure = BrokerUnavailableException("토스 연결 실패")
            service.onState(live)
            trading.accountFailure = null
            trading.openOrders += record("APP-3", OrderStatus.PENDING, filled = 0)

            service.syncOrderStreams()

            assertThat(orders.findProgress(user, "APP-3")).isNotNull()
        }

        @Test
        @DisplayName("주문 채널 구독이 거부된 연결은 살아 있는 스트림으로 보지 않아 맞추지 않음")
        fun rejectedOrderTopicIsNotLive() {
            service.onState(
                FeedState(
                    user,
                    FeedStatus.CONNECTED,
                    recovered = false,
                    rejected = setOf(FeedTopic.MyOrders(user)),
                )
            )

            assertThat(trading.orderListReads).isZero()
        }
    }

    @Test
    @DisplayName("설치가 끝났고 토스 키가 쓸 만한 활성 사용자만 본인 주문 채널을 구독하고, 키가 없어지면 거둠")
    fun subscribesEligibleOwners() {
        val other = UserId.from(Ulid.of(TradingFixtures.now, ByteArray(10) { 5 }))
        users.save(TradingFixtures.account(other))

        service.syncOrderStreams()
        assertThat(feed.declared)
            .containsExactlyEntriesOf(mapOf(user to setOf(FeedTopic.MyOrders(user))))

        credentials.upsert(tossKey(user, CredentialStatus.REJECTED))
        service.syncOrderStreams()
        assertThat(feed.declared).isEmpty()
        assertThat(feed.released).containsExactly(user)
    }

    private fun event(record: banghak.stock.core.domain.trading.BrokerOrderRecord) =
        OrderEvent(user, OrderEventType.FILL, record)

    private fun record(brokerOrderId: String, status: OrderStatus, filled: Long) =
        TradingFixtures.brokerRecord(
            brokerOrderId = brokerOrderId,
            status = status,
            filled = Quantity.of(filled),
        )

    private fun tossKey(owner: UserId, status: CredentialStatus) =
        CredentialMeta(CredentialKind.TOSS, owner, status, "abcd", TradingFixtures.now, null)

    private fun statusChanges(): List<OrderStatusChanged> =
        events
            .replay(user, null, 0)
            .map { it.payload }
            .filterIsInstance<OrderStatusChanged>()
            .toList()
}
