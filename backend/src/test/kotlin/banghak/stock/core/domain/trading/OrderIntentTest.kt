package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.TradingFixtures.autoBuy
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.domain.trading.TradingFixtures.limitBuy
import banghak.stock.core.domain.trading.TradingFixtures.manual
import banghak.stock.core.domain.trading.TradingFixtures.now
import banghak.stock.core.domain.trading.TradingFixtures.nvidia
import banghak.stock.core.domain.trading.TradingFixtures.samsung
import banghak.stock.core.domain.trading.TradingFixtures.usd
import banghak.stock.core.domain.trading.TradingFixtures.user
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class OrderIntentTest {
    private fun market(
        side: OrderSide,
        quantity: Quantity?,
        amount: Money?,
        symbol: Symbol = nvidia,
    ) =
        OrderIntent(
            user,
            symbol,
            side,
            OrderKind.MARKET,
            TimeInForce.DAY,
            null,
            quantity,
            amount,
            OrderOrigin.MANUAL,
            manual,
            now,
        )

    @Nested
    @DisplayName("지정가 형태")
    inner class LimitShape {
        @Test
        @DisplayName("가격과 수량이 있어야 하고 주문 금액은 없어야 함")
        fun requiresPriceAndQuantityWithoutAmount() {
            assertThat(limitBuy().notional()).isEqualTo(krw("700000"))
            assertThatThrownBy { limitBuy(price = krw("0")) }
                .isInstanceOf(InvalidValueException::class.java)
            assertThatThrownBy { limitBuy(quantity = Quantity.ZERO) }
                .isInstanceOf(InvalidValueException::class.java)
            assertThatThrownBy {
                    OrderIntent(
                        user,
                        samsung,
                        OrderSide.BUY,
                        OrderKind.LIMIT,
                        TimeInForce.DAY,
                        krw("70000"),
                        Quantity.of(1),
                        krw("1"),
                        OrderOrigin.MANUAL,
                        manual,
                        now,
                    )
                }
                .isInstanceOf(InvalidValueException::class.java)
        }

        @Test
        @DisplayName("통화와 수량 자릿수가 시장에 맞아야 함")
        fun currencyAndScaleMustMatchMarket() {
            assertThatThrownBy { limitBuy(price = usd("70")) }
                .isInstanceOf(InvalidValueException::class.java)
            assertThatThrownBy { limitBuy(quantity = Quantity.of("1.5")) }
                .isInstanceOf(InvalidValueException::class.java)
            assertThat(
                    limitBuy(symbol = nvidia, price = usd("120.50"), quantity = Quantity.of("1.5"))
                        .notional()
                )
                .isEqualTo(usd("180.75"))
        }
    }

    @Nested
    @DisplayName("시장가 형태(토스 예외 두 가지)")
    inner class MarketShape {
        @Test
        @DisplayName("국내 시장가는 만들 수 없음")
        fun rejectsKoreanMarketOrders() {
            assertThatThrownBy {
                    market(OrderSide.SELL, Quantity.of("1.5"), null, symbol = samsung)
                }
                .isInstanceOf(InvalidValueException::class.java)
        }

        @Test
        @DisplayName("미국 시장가 매도는 수량으로, 매수는 주문 금액으로만 냄")
        fun usSellByQuantityAndBuyByAmount() {
            val sell = market(OrderSide.SELL, Quantity.of("0.123456"), null)
            assertThat(sell.notional(usd("100.00"))).isEqualTo(usd("12.35"))
            assertThatThrownBy { sell.notional() }.isInstanceOf(InvalidValueException::class.java)
            val buy = market(OrderSide.BUY, null, usd("500.00"))
            assertThat(buy.notional()).isEqualTo(usd("500.00"))
            assertThatThrownBy { market(OrderSide.SELL, null, usd("1.00")) }
                .isInstanceOf(InvalidValueException::class.java)
            assertThatThrownBy { market(OrderSide.BUY, Quantity.of(1), null) }
                .isInstanceOf(InvalidValueException::class.java)
        }
    }

    @Nested
    @DisplayName("유효 조건")
    inner class TimeInForceShape {
        @Test
        @DisplayName("CLS 는 미국 지정가에만, OPG 는 국내에만 허용함")
        fun clsOnlyUsLimitAndOpgOnlyKorea() {
            assertThat(
                    limitBuy(
                        symbol = nvidia,
                        price = usd("1.00"),
                        quantity = Quantity.of(1),
                        timeInForce = TimeInForce.CLS,
                    )
                )
                .isNotNull()
            assertThatThrownBy { limitBuy(timeInForce = TimeInForce.CLS) }
                .isInstanceOf(InvalidValueException::class.java)
            assertThat(limitBuy(timeInForce = TimeInForce.OPG)).isNotNull()
            assertThatThrownBy {
                    limitBuy(symbol = nvidia, price = usd("1.00"), timeInForce = TimeInForce.OPG)
                }
                .isInstanceOf(InvalidValueException::class.java)
        }
    }

    @Nested
    @DisplayName("출처와 트리거")
    inner class OriginAndTrigger {
        @Test
        @DisplayName("트리거 종류와 출처가 맞아야 하고 자동 주문은 지정가만 냄")
        fun originMustMatchTriggerAndAutoIsLimitOnly() {
            val auto = limitBuy(trigger = autoBuy, origin = OrderOrigin.AUTO_BUY)
            assertThat(auto.isAutomatic).isTrue()
            assertThat(limitBuy().isAutomatic).isFalse()
            assertThatThrownBy { limitBuy(trigger = autoBuy, origin = OrderOrigin.MANUAL) }
                .isInstanceOf(InvalidValueException::class.java)
            assertThatThrownBy { limitBuy(trigger = manual, origin = OrderOrigin.AUTO_BUY) }
                .isInstanceOf(InvalidValueException::class.java)
            assertThatThrownBy {
                    OrderIntent(
                        user,
                        nvidia,
                        OrderSide.BUY,
                        OrderKind.MARKET,
                        TimeInForce.DAY,
                        null,
                        null,
                        usd("10.00"),
                        OrderOrigin.AUTO_BUY,
                        autoBuy,
                        now,
                    )
                }
                .isInstanceOf(InvalidValueException::class.java)
        }

        @Test
        @DisplayName("사람의 정정은 원주문 출처를 그대로 이어받음")
        fun manualAmendKeepsOriginalOrigin() {
            val amend = ManualAmendTrigger(TradingFixtures.device, "B-1")
            assertThat(limitBuy(trigger = amend, origin = OrderOrigin.AUTO_BUY).isAutomatic)
                .isFalse()
            assertThat(limitBuy(trigger = amend, origin = OrderOrigin.MANUAL).origin)
                .isEqualTo(OrderOrigin.MANUAL)
        }
    }
}
