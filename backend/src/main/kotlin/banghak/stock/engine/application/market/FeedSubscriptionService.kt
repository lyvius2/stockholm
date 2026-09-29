package banghak.stock.engine.application.market

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.FeedTopic
import banghak.stock.core.port.RealtimeFeedPort
import banghak.stock.core.usecase.FeedDemand
import banghak.stock.core.usecase.FeedSubscriptionUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

@Service
@Profile(RuntimeProfiles.ENGINE)
class FeedSubscriptionService(private val feed: RealtimeFeedPort) : FeedSubscriptionUseCase {
    // 합친 결과를 선언하는 순서가 뒤바뀌면 옛 구독이 남으므로 한 번에 하나씩 선언함
    private val lock = ReentrantLock()
    private val demands = mutableMapOf<UserId, MutableMap<FeedDemand, Set<FeedTopic>>>()

    override fun require(owner: UserId, demand: FeedDemand, topics: Set<FeedTopic>) =
        lock.withLock {
            val byDemand = demands.getOrPut(owner) { mutableMapOf() }
            if (byDemand[demand] == topics) return@withLock
            byDemand[demand] = topics
            feed.declare(owner, byDemand.values.flatten().toSet())
        }

    override fun release(owner: UserId, demand: FeedDemand) = lock.withLock {
        val byDemand = demands[owner] ?: return@withLock
        if (byDemand.remove(demand) == null) return@withLock
        if (byDemand.isEmpty()) {
            demands.remove(owner)
            feed.release(owner)
        } else feed.declare(owner, byDemand.values.flatten().toSet())
    }
}
