package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class BrokerOrderRecordTest {
    private val unfilled =
        TradingFixtures.brokerRecord(intent = TradingFixtures.limitBuy(quantity = Quantity.of(100)))
    private val price = OrderAmendment(TradingFixtures.krw("71000"), null)

    @Test
    @DisplayName("정정 수량은 잔량까지: 체결이 없는 100주 주문은 100주까지 되고 101주는 안 됨")
    fun amendQuantityUpToRemaining() {
        assertThatCode { unfilled.requireAmendable(OrderAmendment(null, Quantity.of(100))) }
            .doesNotThrowAnyException()
        assertThatThrownBy { unfilled.requireAmendable(OrderAmendment(null, Quantity.of(101))) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("일부 체결된 주문은 잔량까지: 100주 중 24주 체결이면 76주는 되고 77주는 안 됨")
    fun partiallyFilledUpToRemaining() {
        val partlyFilled =
            unfilled.copy(status = OrderStatus.PARTIALLY_FILLED, filledQuantity = Quantity.of(24))

        assertThat(partlyFilled.remaining()).isEqualTo(Quantity.of(76))
        assertThatCode { partlyFilled.requireAmendable(price) }.doesNotThrowAnyException()
        assertThatCode { partlyFilled.requireAmendable(OrderAmendment(null, Quantity.of(76))) }
            .doesNotThrowAnyException()
        assertThatThrownBy { partlyFilled.requireAmendable(OrderAmendment(null, Quantity.of(77))) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("처리 중(취소·정정 대기)이거나 닫힌 주문은 정정할 수 없음")
    fun onlyChangeableStatus() {
        listOf(
                OrderStatus.PENDING_CANCEL,
                OrderStatus.PENDING_AMEND,
                OrderStatus.FILLED,
                OrderStatus.UNKNOWN,
            )
            .forEach { status ->
                assertThatThrownBy { unfilled.copy(status = status).requireAmendable(price) }
                    .describedAs(status.name)
                    .isInstanceOf(InvalidValueException::class.java)
            }
    }

    @Test
    @DisplayName("시장가 주문은 정정하지 않음(정정은 지정가만)")
    fun marketOrdersAreNotAmended() {
        val market = unfilled.copy(kind = OrderKind.MARKET, limitPrice = null)

        assertThatThrownBy { market.requireAmendable(price) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("정정·취소 가능 상태는 열려 있고 처리 중이 아닌 것뿐임")
    fun changeableStatuses() {
        assertThat(OrderStatus.entries.filter { it.isChangeable })
            .containsExactly(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED)
    }
}
