package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.FeedTopic
import banghak.stock.core.port.FeedListener
import banghak.stock.core.port.RealtimeFeedPort
import banghak.stock.engine.config.ExternalEndpointProperties
import banghak.stock.shared.config.RuntimeProfiles
import jakarta.annotation.PreDestroy
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import okhttp3.OkHttpClient
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 토스 웹소켓 설정.
 * 서버는 클라이언트에게서 180초간 아무것도 못 받으면 끊으므로 표준 ping 을 [pingInterval] 마다 보냄(원문 권장 60초).
 * 재연결은 [reconnectInitial] 에서 두 배씩 [reconnectMax] 까지 늘림.
 * 선언 승인이 [ackTimeout] 안에 오지 않으면 끊고 다시 붙음.
 * 처리되지 않은 내 주문 이벤트가 [orderEventCapacity] 를 넘으면 끊고 재연결·재동기로 넘어감.
 */
data class TossFeedSettings(
    val pingInterval: Duration = Duration.ofSeconds(60),
    val reconnectInitial: Duration = Duration.ofSeconds(1),
    val reconnectMax: Duration = Duration.ofSeconds(60),
    val declareDebounce: Duration = Duration.ofMillis(250),
    val ackTimeout: Duration = Duration.ofSeconds(10),
    val orderEventCapacity: Int = 1_000,
)

/**
 * 토스 실시간 어댑터.
 * 키 주인마다 연결 하나(계정당 동시 연결 2개 한도 안)를 두고 구독을 선언형으로 관리함.
 * 공용 시세는 admin 이, 내 주문은 각 사용자가 주인임.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class TossRealtimeFeed(
    sharedClient: OkHttpClient,
    private val endpoints: ExternalEndpointProperties,
    private val tokens: TossTokenCache,
    private val accountSeqs: TossAccountCache,
    private val settings: TossFeedSettings,
) : RealtimeFeedPort {
    // 웹소켓은 업그레이드 뒤 읽기 타임아웃을 두지 않고 표준 ping 으로 연결을 살림
    private val client: OkHttpClient =
        sharedClient
            .newBuilder()
            .readTimeout(Duration.ZERO)
            .pingInterval(settings.pingInterval)
            .build()
    private val scheduler: ScheduledExecutorService =
        Executors.newScheduledThreadPool(
            2,
            Thread.ofPlatform().daemon().name("toss-feed-", 0).factory(),
        )
    private val connections = ConcurrentHashMap<UserId, TossFeedConnection>()
    private val listeners = CopyOnWriteArrayList<FeedListener>()

    override fun declare(owner: UserId, topics: Set<FeedTopic>) {
        requireOwnedOrderTopics(owner, topics)
        if (topics.size > RealtimeFeedPort.MAX_TOPICS)
            throw InvalidValueException(
                "연결당 구독은 ${RealtimeFeedPort.MAX_TOPICS}건까지임: ${topics.size}"
            )
        connections.computeIfAbsent(owner, ::newConnection).declare(topics)
    }

    override fun release(owner: UserId) {
        connections.remove(owner)?.close()
    }

    override fun addListener(listener: FeedListener) {
        listeners += listener
    }

    @PreDestroy
    fun shutdown() {
        connections.values.forEach(TossFeedConnection::close)
        connections.clear()
        scheduler.shutdownNow()
    }

    private fun newConnection(owner: UserId): TossFeedConnection =
        TossFeedConnection(
            owner,
            client,
            endpoints.tossWebSocketUrl,
            tokens,
            accountSeqs,
            scheduler,
            settings,
            listeners,
        )

    // 내 주문은 그 사용자 본인의 키 연결로만 구독함(다른 사용자 계좌를 섞지 않음)
    private fun requireOwnedOrderTopics(owner: UserId, topics: Set<FeedTopic>) {
        topics
            .filterIsInstance<FeedTopic.MyOrders>()
            .firstOrNull { it.userId != owner }
            ?.let {
                throw InvalidValueException("${it.userId} 의 주문은 $owner 연결로 구독할 수 없음")
            }
    }
}
