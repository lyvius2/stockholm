package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.trading.TradingFixtures.autoBuy
import banghak.stock.core.domain.trading.TradingFixtures.brokerOrder
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.domain.trading.TradingFixtures.limitBuy
import banghak.stock.core.domain.trading.TradingFixtures.usd
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class OrderAmendmentAndBrokerOrderTest {
    @Test
    @DisplayName("정정은 가격·수량 중 하나는 있어야 하고 미국은 가격만 바꿀 수 있음")
    fun amendmentShape() {
        assertThatThrownBy { OrderAmendment(null, null) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThat(OrderAmendment.forMarket(Market.KR, krw("71000"), null)).isNotNull()
        assertThat(OrderAmendment.forMarket(Market.KR, null, Quantity.of(5))).isNotNull()
        assertThat(OrderAmendment.forMarket(Market.KR, krw("71000"), Quantity.of(5))).isNotNull()
        assertThat(OrderAmendment.forMarket(Market.US, usd("120.00"), null)).isNotNull()
        assertThatThrownBy { OrderAmendment.forMarket(Market.US, usd("120.00"), Quantity.of(1)) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { OrderAmendment.forMarket(Market.KR, usd("1.00"), null) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("상태 11종 전부에 대해 열림·정정·취소 가능 여부가 정해져 있음(출처 무관)")
    fun statusTable() {
        val expected =
            mapOf(
                OrderStatus.PENDING to Triple(true, true, true),
                OrderStatus.PARTIALLY_FILLED to Triple(true, true, true),
                OrderStatus.PENDING_CANCEL to Triple(true, false, false),
                OrderStatus.PENDING_AMEND to Triple(true, false, false),
                OrderStatus.FILLED to Triple(false, false, false),
                OrderStatus.CANCELLED to Triple(false, false, false),
                OrderStatus.REJECTED to Triple(false, false, false),
                OrderStatus.CANCEL_REJECTED to Triple(false, false, false),
                OrderStatus.AMEND_REJECTED to Triple(false, false, false),
                OrderStatus.REPLACED to Triple(false, false, false),
                OrderStatus.UNKNOWN to Triple(false, false, false),
            )
        assertThat(expected.keys).containsExactlyInAnyOrder(*OrderStatus.entries.toTypedArray())
        for ((status, triple) in expected) {
            for (intent in
                listOf(limitBuy(), limitBuy(trigger = autoBuy, origin = OrderOrigin.AUTO_BUY))) {
                val order = brokerOrder(intent = intent, status = status)
                assertThat(Triple(order.isOpen, order.canAmend, order.canCancel))
                    .describedAs("$status/${intent.origin}")
                    .isEqualTo(triple)
            }
        }
    }

    @Test
    @DisplayName("정정 수량은 잔량까지만 허용함: 100주 중 24주 체결이면 76주는 되고 77주는 안 됨")
    fun amendQuantityUpToRemaining() {
        val order =
            brokerOrder(
                intent = limitBuy(quantity = Quantity.of(100)),
                status = OrderStatus.PARTIALLY_FILLED,
                filled = Quantity.of(24),
            )
        assertThat(order.remaining()).isEqualTo(Quantity.of(76))
        order.requireAmendable(OrderAmendment(null, Quantity.of(76)))
        assertThatThrownBy { order.requireAmendable(OrderAmendment(null, Quantity.of(77))) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy {
                brokerOrder(status = OrderStatus.PENDING_CANCEL)
                    .requireAmendable(OrderAmendment(krw("1"), null))
            }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy {
                brokerOrder(intent = limitBuy(quantity = Quantity.of(1)), filled = Quantity.of(2))
            }
            .isInstanceOf(InvalidValueException::class.java)
    }
}
