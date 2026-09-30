package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.trading.ConditionalFixtures.leg
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.domain.trading.TradingFixtures.usd
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ConditionalOrderIntentTest {
    @Nested
    @DisplayName("형태")
    inner class Shape {
        @Test
        @DisplayName("SINGLE 은 둘째 조건을 받지 않음")
        fun singleTakesOneLeg() {
            assertThatThrownBy {
                    ConditionalFixtures.single().copy(second = leg(OrderSide.SELL, krw("80000")))
                }
                .isInstanceOf(InvalidValueException::class.java)
        }

        @Test
        @DisplayName("OCO 는 두 조건 모두 매도여야 함")
        fun ocoSellsBothLegs() {
            assertThatThrownBy {
                    ConditionalFixtures.oco().copy(first = leg(OrderSide.BUY, krw("80000")))
                }
                .isInstanceOf(InvalidValueException::class.java)
        }

        @Test
        @DisplayName("OCO 첫 감시가가 둘째와 같으면 거부하고 1원이라도 높으면 받음")
        fun ocoFirstTriggerAboveSecond() {
            assertThatThrownBy {
                    ConditionalFixtures.oco(takeProfit = krw("60000"), stopLoss = krw("60000"))
                }
                .isInstanceOf(InvalidValueException::class.java)

            assertThat(ConditionalFixtures.oco(takeProfit = krw("60001"), stopLoss = krw("60000")))
                .isNotNull
        }

        @Test
        @DisplayName("OTO 는 첫 조건 매수, 둘째 조건 매도여야 함")
        fun otoBuysThenSells() {
            val oto =
                ConditionalFixtures.oco()
                    .copy(
                        type = ConditionalOrderType.OTO,
                        first = leg(OrderSide.BUY, krw("65000")),
                        second = leg(OrderSide.SELL, krw("75000")),
                    )
            assertThat(oto.legs).hasSize(2)

            assertThatThrownBy { oto.copy(second = leg(OrderSide.BUY, krw("75000"))) }
                .isInstanceOf(InvalidValueException::class.java)
            assertThatThrownBy { oto.copy(second = null) }
                .isInstanceOf(InvalidValueException::class.java)
        }
    }

    @Test
    @DisplayName("수량은 1주 이상 정수 주수만 받음")
    fun wholeSharesOnly() {
        assertThatThrownBy { ConditionalFixtures.single(quantity = Quantity.of("0.5")) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { ConditionalFixtures.single(quantity = Quantity.ZERO) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("가격 통화는 시장 통화와 같아야 함")
    fun pricesInMarketCurrency() {
        assertThatThrownBy { ConditionalFixtures.single(trigger = usd("50"), price = usd("50")) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("감시가와 주문 가격은 0보다 크고 통화가 같아야 함")
    fun legPricesArePositiveInOneCurrency() {
        assertThatThrownBy { leg(OrderSide.SELL, krw("0")) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { leg(OrderSide.SELL, krw("100"), usd("100")) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("고액 판정 금액은 조건별 주문 가격 × 수량 중 큰 쪽임")
    fun largestNotionalIsTheBiggerLeg() {
        assertThat(ConditionalFixtures.oco().largestNotional()).isEqualTo(krw("800000"))
    }
}
