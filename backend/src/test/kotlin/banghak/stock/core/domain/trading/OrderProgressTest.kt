package banghak.stock.core.domain.trading

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class OrderProgressTest {
    @Test
    @DisplayName("체결 수량이 줄어든 기록은 뒤처진 것임")
    fun fewerFilledIsBehind() {
        val current = OrderProgress(OrderStatus.PARTIALLY_FILLED, Quantity.of(4))

        assertThat(current.isAheadOf(record(OrderStatus.PARTIALLY_FILLED, filled = 3))).isTrue()
        assertThat(current.isAheadOf(record(OrderStatus.PARTIALLY_FILLED, filled = 4))).isFalse()
        assertThat(current.isAheadOf(record(OrderStatus.FILLED, filled = 10))).isFalse()
    }

    @Test
    @DisplayName("이미 닫힌 주문을 다시 여는 기록은 뒤처진 것이고, 모름 상태는 무엇으로든 바뀔 수 있음")
    fun closedOrderDoesNotReopen() {
        val filled = OrderProgress(OrderStatus.FILLED, Quantity.of(10))
        val unknown = OrderProgress(OrderStatus.UNKNOWN, Quantity.ZERO)

        assertThat(filled.isAheadOf(record(OrderStatus.PENDING, filled = 10))).isTrue()
        assertThat(unknown.isAheadOf(record(OrderStatus.PENDING, filled = 0))).isFalse()
    }

    @Test
    @DisplayName("열린 주문끼리의 상태 변화(체결 대기 ↔ 정정 대기)는 받아들임")
    fun openStatesMayChange() {
        val pendingAmend = OrderProgress(OrderStatus.PENDING_AMEND, Quantity.ZERO)

        assertThat(pendingAmend.isAheadOf(record(OrderStatus.PENDING, filled = 0))).isFalse()
    }

    private fun record(status: OrderStatus, filled: Long) =
        TradingFixtures.brokerRecord(status = status, filled = Quantity.of(filled))
}
