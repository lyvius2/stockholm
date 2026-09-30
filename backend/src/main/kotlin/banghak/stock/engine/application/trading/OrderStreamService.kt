package banghak.stock.engine.application.trading

import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.CredentialStatus
import banghak.stock.core.domain.account.UserStatus
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.trading.BrokerOrderRecord
import banghak.stock.core.domain.trading.ClosedOrdersQuery
import banghak.stock.core.domain.trading.FeedState
import banghak.stock.core.domain.trading.FeedStatus
import banghak.stock.core.domain.trading.FeedTopic
import banghak.stock.core.domain.trading.OrderEvent
import banghak.stock.core.port.BrokerOrderStorePort
import banghak.stock.core.port.CredentialMetaPort
import banghak.stock.core.port.DevicePort
import banghak.stock.core.port.FeedListener
import banghak.stock.core.port.InstallationPort
import banghak.stock.core.port.RealtimeFeedPort
import banghak.stock.core.port.TradingPort
import banghak.stock.core.port.UserAccountPort
import banghak.stock.core.usecase.FeedDemand
import banghak.stock.core.usecase.FeedSubscriptionUseCase
import banghak.stock.core.usecase.SyncOrderStreamsUseCase
import banghak.stock.shared.config.RuntimeProfiles
import jakarta.annotation.PostConstruct
import java.time.Clock
import java.time.LocalDate
import java.time.Period
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service

/**
 * 토스 실시간 주문 채널을 주문 기록에 반영함.
 * 채널은 연결 안에서만 빠짐없이 오고 끊긴 구간은 다시 오지 않으므로, 스트림이 살아날 때마다 미체결을 다시 받아 맞춤.
 * 데몬이 꺼져 있던 동안도 끊긴 구간이라 기동 뒤 첫 연결에서도 맞춤.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class OrderStreamService(
    private val feed: RealtimeFeedPort,
    private val subscriptions: FeedSubscriptionUseCase,
    private val trading: TradingPort,
    private val journal: OrderJournal,
    private val orders: BrokerOrderStorePort,
    private val installations: InstallationPort,
    private val users: UserAccountPort,
    private val credentials: CredentialMetaPort,
    private val devices: DevicePort,
    private val clock: Clock,
) : FeedListener, SyncOrderStreamsUseCase {
    private val liveOwners = ConcurrentHashMap.newKeySet<UserId>()
    private val resyncPending = ConcurrentHashMap.newKeySet<UserId>()
    private val closedSyncedFrom = ConcurrentHashMap<UserId, LocalDate>()

    // 수신 스레드의 재동기와 스케줄러의 재시도가 같은 사용자를 동시에 맞추지 않게 함
    private val resyncLock = ReentrantLock()

    @PostConstruct fun listen() = feed.addListener(this)

    // 피드 실행기는 수신자 예외를 로그만 남기고 다음 이벤트로 넘어감.
    // 연결이 살아 있으면 재동기 계기가 없으므로, 놓친 이벤트는 재동기 대기열에 올려 되찾음
    override fun onOrderEvent(event: OrderEvent) {
        try {
            apply(devices.localDevice(), event.userId, event.record)
        } catch (e: RuntimeException) {
            log.warn("주문 이벤트 반영 실패({}). 재동기로 맞춤", e::class.simpleName)
            resyncPending += event.userId
        }
    }

    // 읽지 못한 이벤트는 내용을 몰라 주문 전체를 다시 받아 맞춤(다음 구독 맞추기 때, 1분 안)
    override fun onOrderEventLost(owner: UserId) {
        log.warn("읽지 못한 주문 이벤트가 있음. 재동기로 맞춤")
        resyncPending += owner
    }

    override fun onState(state: FeedState) {
        val owner = state.owner
        if (!state.isOrderStreamLive(owner)) {
            liveOwners.remove(owner)
            if (state.status == FeedStatus.CONNECTED && FeedTopic.MyOrders(owner) in state.rejected)
                log.warn("토스가 주문 채널 구독을 거부함. 계좌 상태를 확인할 것")
            return
        }
        // 스트림이 새로 살아났을 때만 맞춤(구독을 바꿔 다시 승인된 것은 끊긴 구간이 없음)
        if (liveOwners.add(owner)) {
            resyncPending += owner
            resync(owner)
        }
    }

    override fun syncOrderStreams() {
        val eligible = eligibleOwners()
        users.findAll().forEach { account ->
            val owner = account.userId
            if (owner in eligible)
                subscriptions.require(owner, FeedDemand.MY_ORDERS, setOf(FeedTopic.MyOrders(owner)))
            else subscriptions.release(owner, FeedDemand.MY_ORDERS)
        }
        resyncPending.toList().forEach(::resync)
    }

    // 실패하면 목록에 남겨 다음 확인 때 다시 함
    private fun resync(owner: UserId) = resyncLock.withLock {
        if (owner !in resyncPending) return@withLock
        try {
            reconcile(owner)
            resyncPending -= owner
        } catch (e: RuntimeException) {
            log.warn("주문 재동기 실패({}). 다음 확인 때 다시 함", e::class.simpleName)
        }
    }

    // 미체결 → 종료 주문(끊긴 사이 생기고 끝난 주문 포함) → 그래도 로컬에만 열린 주문의 상세 순서로 맞춤
    private fun reconcile(owner: UserId) {
        val device = devices.localDevice()
        val open = Market.entries.flatMap { trading.openOrders(owner, it) }
        open.forEach { apply(device, owner, it) }
        reconcileClosedOrders(device, owner)
        val closedMeanwhile =
            orders.openBrokerOrderIds(owner) - open.map { it.brokerOrderId }.toSet()
        closedMeanwhile.forEach { brokerOrderId ->
            try {
                apply(device, owner, trading.lookupOrder(owner, brokerOrderId))
            } catch (e: InvalidValueException) {
                log.warn("로컬에 열린 주문 하나를 토스에서 찾지 못함. 로컬 상태를 그대로 둠")
            }
        }
    }

    // 마지막으로 끝까지 읽은 날부터 오늘까지 읽음(처음이면 최근 30일).
    // 상한에 걸려 뒤가 남으면 그 날을 넘기지 않아 다음에 다시 읽음
    private fun reconcileClosedOrders(device: DeviceId, owner: UserId) {
        val today = clock.instant().atZone(Market.KR.zone).toLocalDate()
        val from = closedSyncedFrom[owner] ?: today.minus(CLOSED_BACKFILL)
        val truncated =
            Market.entries
                .map { market ->
                    trading.closedOrderPages(
                        owner,
                        ClosedOrdersQuery(market, from, today, null),
                        MAX_CLOSED_PAGES,
                    )
                }
                .onEach { page -> page.orders.forEach { apply(device, owner, it) } }
                .any { it.isTruncated }
        if (truncated) log.warn("종료 주문이 {}쪽을 넘어 일부만 반영함. 다음 재동기 때 이어서 읽음", MAX_CLOSED_PAGES)
        else closedSyncedFrom[owner] = today
    }

    // 같은 주문을 다른 쪽(실시간 이벤트·재동기)이 먼저 고쳤으면 다시 읽어 뒤처졌는지부터 판정함
    private fun apply(device: DeviceId, owner: UserId, record: BrokerOrderRecord) {
        repeat(MAX_CONFLICT_RETRIES) {
            try {
                journal.recordBrokerProgress(device, owner, record)
                return
            } catch (e: OptimisticLockingFailureException) {
                log.info("같은 주문을 동시에 고쳐 다시 반영함")
            }
        }
        journal.recordBrokerProgress(device, owner, record)
    }

    private fun eligibleOwners(): Set<UserId> {
        if (installations.load()?.setupState?.isComplete != true) return emptySet()
        return users
            .findAll()
            .filter { it.status == UserStatus.ACTIVE }
            .map { it.userId }
            .filter { owner ->
                credentials.findByUser(owner).any {
                    it.kind == CredentialKind.TOSS && it.status in USABLE_KEY
                }
            }
            .toSet()
    }

    companion object {
        private val log = LoggerFactory.getLogger(OrderStreamService::class.java)
        private val USABLE_KEY = setOf(CredentialStatus.VERIFIED, CredentialStatus.UNREACHABLE)
        private const val MAX_CONFLICT_RETRIES = 2
        private const val MAX_CLOSED_PAGES = 20

        // 처음 재동기할 때 거슬러 읽는 기간.
        // 그보다 오래된 내역은 거래내역 적재(F20)가 맡음
        private val CLOSED_BACKFILL: Period = Period.ofDays(30)
    }
}
