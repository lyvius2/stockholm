package banghak.stock.engine.application.market

import banghak.stock.core.domain.trading.FeedTopic
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.usecase.FeedDemand
import banghak.stock.support.fakes.FakeRealtimeFeed
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FeedSubscriptionServiceTest {
    private val feed = FakeRealtimeFeed()
    private val service = FeedSubscriptionService(feed)
    private val user = TradingFixtures.user
    private val myOrders = setOf(FeedTopic.MyOrders(user))

    @Test
    @DisplayName("같은 구독을 다시 요구하면 다시 선언하지 않음(선언 빈도 한도를 아낌)")
    fun skipsUnchangedDemand() {
        service.require(user, FeedDemand.MY_ORDERS, myOrders)
        feed.declared.clear()

        service.require(user, FeedDemand.MY_ORDERS, myOrders)

        assertThat(feed.declared).isEmpty()
    }

    @Test
    @DisplayName("마지막 구독을 거두면 연결을 닫고, 없는 구독을 거두면 아무것도 하지 않음")
    fun releasesConnectionWhenNothingLeft() {
        service.release(user, FeedDemand.MY_ORDERS)
        assertThat(feed.released).isEmpty()

        service.require(user, FeedDemand.MY_ORDERS, myOrders)
        service.release(user, FeedDemand.MY_ORDERS)

        assertThat(feed.released).containsExactly(user)
        assertThat(feed.declared).isEmpty()
    }
}
