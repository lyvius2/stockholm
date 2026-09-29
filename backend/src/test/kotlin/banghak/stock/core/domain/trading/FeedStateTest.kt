package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FeedStateTest {
    private val user = TradingFixtures.user
    private val myOrders = FeedTopic.MyOrders(user)

    @Test
    @DisplayName("내 주문 스트림은 CONNECTED 이고 그 사용자의 주문 구독이 승인됐을 때만 살아 있음")
    fun orderStreamIsLiveOnlyWhenAccepted() {
        val accepted =
            FeedState(user, FeedStatus.CONNECTED, recovered = false, accepted = setOf(myOrders))
        val rejected =
            FeedState(user, FeedStatus.CONNECTED, recovered = false, rejected = setOf(myOrders))
        val disconnected = FeedState(user, FeedStatus.DISCONNECTED, recovered = false)

        assertThat(accepted.isOrderStreamLive(user)).isTrue()
        assertThat(rejected.isOrderStreamLive(user)).isFalse()
        assertThat(disconnected.isOrderStreamLive(user)).isFalse()
    }

    @Test
    @DisplayName("끊긴 상태에는 복구 여부나 승인 결과를 담을 수 없음")
    fun rejectsAcknowledgementOutsideConnected() {
        assertThatThrownBy { FeedState(user, FeedStatus.DISCONNECTED, recovered = true) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy {
                FeedState(
                    user,
                    FeedStatus.ACCESS_DENIED,
                    recovered = false,
                    accepted = setOf(myOrders),
                )
            }
            .isInstanceOf(InvalidValueException::class.java)
    }
}
