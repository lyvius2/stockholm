package banghak.stock.core.domain.guardrail

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.guardrail.GuardrailFixtures.context
import banghak.stock.core.domain.guardrail.GuardrailFixtures.kst
import banghak.stock.core.domain.guardrail.GuardrailFixtures.quote
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.MarketSession
import banghak.stock.core.domain.market.TradingDay
import banghak.stock.core.domain.trading.OrderIntent
import banghak.stock.core.domain.trading.OrderKind
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.OrderStatus
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TimeInForce
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.domain.trading.TradingFixtures.autoBuy
import banghak.stock.core.domain.trading.TradingFixtures.brokerRecord
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.domain.trading.TradingFixtures.limitBuy
import banghak.stock.core.domain.trading.TradingFixtures.manual
import banghak.stock.core.domain.trading.TradingFixtures.nvidia
import banghak.stock.core.domain.trading.TradingFixtures.usd
import banghak.stock.core.domain.trading.TradingFixtures.user
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ManualOrderRulesTest {
    private val regularKr = kst("10:00")

    private fun usMarketSell(quantity: String = "0.5") =
        OrderIntent(
            user,
            nvidia,
            OrderSide.SELL,
            OrderKind.MARKET,
            TimeInForce.DAY,
            null,
            Quantity.of(quantity),
            null,
            OrderOrigin.MANUAL,
            manual,
            kst("23:00"),
        )

    private fun usMarketBuy() =
        OrderIntent(
            user,
            nvidia,
            OrderSide.BUY,
            OrderKind.MARKET,
            TimeInForce.DAY,
            null,
            null,
            usd("500.00"),
            OrderOrigin.MANUAL,
            manual,
            kst("23:00"),
        )

    @Nested
    @DisplayName("SnapshotFreshness")
    inner class Freshness {
        @Test
        @DisplayName("정확히 30초 지난 스냅샷은 통과하고 31초면 거부함")
        fun boundary() {
            val rule = SnapshotFreshness()
            val fresh =
                context(limitBuy(), now = regularKr.plusSeconds(30), snapshotAsOf = regularKr)
            val stale =
                context(limitBuy(), now = regularKr.plusSeconds(31), snapshotAsOf = regularKr)
            assertThat(rule.check(fresh)).isEqualTo(GuardrailFinding.Clear)
            assertThat(rule.check(stale)).isInstanceOf(GuardrailFinding.Violation::class.java)
        }
    }

    @Nested
    @DisplayName("SessionOpen")
    inner class Session {
        @Test
        @DisplayName("장이 닫혀 있으면 거부, 정규장 밖에서는 지정가만 통과")
        fun closedAndOffHours() {
            val rule = SessionOpen()
            assertThat(rule.check(context(limitBuy(), now = kst("07:00"))))
                .isInstanceOf(GuardrailFinding.Violation::class.java)
            assertThat(rule.check(context(limitBuy(), now = kst("16:00"))))
                .isEqualTo(GuardrailFinding.Clear)
            assertThat(rule.check(context(usMarketBuy(), now = kst("18:00"))))
                .isInstanceOf(GuardrailFinding.Violation::class.java)
            assertThat(rule.check(context(usMarketBuy(), now = kst("23:00"))))
                .isEqualTo(GuardrailFinding.Clear)
            val holiday = TradingDay(Market.KR, GuardrailFixtures.date, emptyList())
            assertThat(rule.check(context(limitBuy(), now = regularKr, tradingDay = holiday)))
                .isInstanceOf(GuardrailFinding.Violation::class.java)
            assertThat(holiday.sessionAt(regularKr)).isEqualTo(MarketSession.CLOSED)
        }
    }

    @Nested
    @DisplayName("MarketOrderScope")
    inner class MarketScope {
        private val rule = MarketOrderScope()

        @Test
        @DisplayName("지정가에는 적용되지 않고, 미국 소수점 매도·금액 매수만 정규장 시작~종료 1시간 전에 통과")
        fun scopeAndWindow() {
            assertThat(rule.appliesTo(limitBuy())).isFalse()
            val end = GuardrailFixtures.usDay.window(MarketSession.REGULAR)!!.end
            assertThat(rule.check(context(usMarketSell(), now = end.minus(Duration.ofHours(1)))))
                .isEqualTo(GuardrailFinding.Clear)
            assertThat(rule.check(context(usMarketSell(), now = end.minus(Duration.ofMinutes(59)))))
                .isInstanceOf(GuardrailFinding.Violation::class.java)
            assertThat(rule.check(context(usMarketSell(), now = kst("22:30"))))
                .isEqualTo(GuardrailFinding.Clear)
            assertThat(rule.check(context(usMarketSell(), now = kst("22:29"))))
                .isInstanceOf(GuardrailFinding.Violation::class.java)
            assertThat(rule.check(context(usMarketBuy(), now = kst("23:00"))))
                .isEqualTo(GuardrailFinding.Clear)
        }

        @Test
        @DisplayName("정수 주수 시장가 매도와 자동 주문의 시장가는 거부")
        fun wholeSharesAndAutomatic() {
            assertThat(rule.check(context(usMarketSell("2"), now = kst("23:00"))))
                .isInstanceOf(GuardrailFinding.Violation::class.java)
            // 자동 주문의 시장가는 OrderIntent 생성 단계에서 이미 막힘
            assertThatThrownBy {
                    usMarketSell()
                        .copy(
                            trigger = autoBuy,
                            origin = OrderOrigin.AUTO_BUY,
                            side = OrderSide.BUY,
                            quantity = null,
                            orderAmount = usd("1.00"),
                        )
                }
                .isInstanceOf(InvalidValueException::class.java)
        }
    }

    @Nested
    @DisplayName("반대 방향·같은 방향 미체결")
    inner class OpenOrders {
        @Test
        @DisplayName("반대 방향 미체결은 거부, 같은 방향은 수동이면 노트·자동이면 거부")
        fun oppositeAndDuplicate() {
            val buy = limitBuy()
            val sell = buy.copy(side = OrderSide.SELL)
            val openSell = brokerRecord(intent = sell, brokerOrderId = "S-1")
            val openBuy = brokerRecord(intent = buy, brokerOrderId = "B-1")
            val filledSell =
                brokerRecord(
                    intent = sell,
                    status = OrderStatus.FILLED,
                    filled = Quantity.of(10),
                    brokerOrderId = "S-2",
                )

            assertThat(
                    OppositeSideOpenOrder()
                        .check(context(buy, regularKr, openOrders = listOf(openSell)))
                )
                .isInstanceOf(GuardrailFinding.Violation::class.java)
            assertThat(
                    OppositeSideOpenOrder()
                        .check(context(buy, regularKr, openOrders = listOf(filledSell)))
                )
                .isEqualTo(GuardrailFinding.Clear)
            assertThat(
                    DuplicateIntent().check(context(buy, regularKr, openOrders = listOf(openBuy)))
                )
                .isInstanceOf(GuardrailFinding.Note::class.java)
            val autoIntent = limitBuy(trigger = autoBuy, origin = OrderOrigin.AUTO_BUY)
            assertThat(
                    DuplicateIntent()
                        .check(context(autoIntent, regularKr, openOrders = listOf(openBuy)))
                )
                .isInstanceOf(GuardrailFinding.Violation::class.java)
            assertThat(DuplicateIntent().check(context(buy, regularKr)))
                .isEqualTo(GuardrailFinding.Clear)
        }
    }

    @Nested
    @DisplayName("HighValueOrder")
    inner class HighValue {
        private val rule = HighValueOrder()

        @Test
        @DisplayName("정확히 1억원은 확인 노트, 1억원 미만은 통과, 30억원은 통과, 30억원 초과는 거부")
        fun boundaries() {
            assertThat(
                    rule.check(
                        context(
                            limitBuy(price = krw("100000"), quantity = Quantity.of(1000)),
                            regularKr,
                        )
                    )
                )
                .isInstanceOf(GuardrailFinding.Note::class.java)
            assertThat(
                    rule.check(
                        context(
                            limitBuy(price = krw("99999"), quantity = Quantity.of(1000)),
                            regularKr,
                        )
                    )
                )
                .isEqualTo(GuardrailFinding.Clear)
            assertThat(
                    rule.check(
                        context(
                            limitBuy(price = krw("3000000"), quantity = Quantity.of(1000)),
                            regularKr,
                        )
                    )
                )
                .isInstanceOf(GuardrailFinding.Note::class.java)
            assertThat(
                    rule.check(
                        context(
                            limitBuy(price = krw("3000001"), quantity = Quantity.of(1000)),
                            regularKr,
                        )
                    )
                )
                .isInstanceOf(GuardrailFinding.Violation::class.java)
        }

        @Test
        @DisplayName("해외 주문은 환율로 환산해 판정하고, 환율이나 시장가 매도의 현재가가 없으면 거부")
        fun foreignNeedsFxAndQuote() {
            val usLimit =
                limitBuy(symbol = nvidia, price = usd("100.00"), quantity = Quantity.of(715))
            assertThat(rule.check(context(usLimit, kst("23:00"))))
                .isInstanceOf(GuardrailFinding.Note::class.java)
            assertThat(rule.check(context(usLimit, kst("23:00"), fx = null)))
                .isInstanceOf(GuardrailFinding.Violation::class.java)
            assertThat(
                    rule.check(
                        context(
                            usMarketSell(),
                            kst("23:00"),
                            quote = quote(usMarketSell(), "100.00", kst("23:00")),
                        )
                    )
                )
                .isEqualTo(GuardrailFinding.Clear)
            assertThat(rule.check(context(usMarketSell(), kst("23:00"), quote = null)))
                .isInstanceOf(GuardrailFinding.Violation::class.java)
        }
    }

    @Test
    @DisplayName("수동 규칙 묶음은 위반을 전부 모으고 노트를 함께 돌려주며 규칙 순서는 이름순임")
    fun chainCollectsEverything() {
        val chain = ManualOrderGuardrails.standard()
        val buy = limitBuy(price = krw("100000"), quantity = Quantity.of(1000))
        val stale =
            context(
                buy,
                now = kst("07:00"),
                openOrders =
                    listOf(
                        brokerRecord(
                            intent = buy.copy(side = OrderSide.SELL),
                            brokerOrderId = "S-1",
                        ),
                        brokerRecord(intent = buy, brokerOrderId = "B-1"),
                    ),
                snapshotAsOf = kst("06:00"),
            )

        val verdict = chain.evaluate(stale) as GuardrailVerdict.Rejected

        assertThat(verdict.violations.map { it.rule })
            .containsExactly("OppositeSideOpenOrder", "SessionOpen", "SnapshotFreshness")
        assertThat(verdict.notes.map { it.rule })
            .containsExactly("DuplicateIntent", "HighValueOrder")
        assertThat(chain.evaluate(context(limitBuy(), regularKr)))
            .isEqualTo(GuardrailVerdict.Passed(emptyList()))
        assertThat(chain.evaluate(context(limitBuy(), regularKr)).isPassed).isTrue()
        assertThat(TradingFixtures.now).isNotNull()
    }
}
