package banghak.stock.core.domain.guardrail

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.guardrail.GuardrailFixtures.context
import banghak.stock.core.domain.guardrail.GuardrailFixtures.key
import banghak.stock.core.domain.guardrail.GuardrailFixtures.kst
import banghak.stock.core.domain.guardrail.GuardrailFixtures.quote
import banghak.stock.core.domain.guardrail.GuardrailFixtures.usdKrw
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.OrderIntent
import banghak.stock.core.domain.trading.OrderKind
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.OrderStatus
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TimeInForce
import banghak.stock.core.domain.trading.TradingFixtures.autoBuy
import banghak.stock.core.domain.trading.TradingFixtures.brokerOrder
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.domain.trading.TradingFixtures.limitBuy
import banghak.stock.core.domain.trading.TradingFixtures.manual
import banghak.stock.core.domain.trading.TradingFixtures.nvidia
import banghak.stock.core.domain.trading.TradingFixtures.samsung
import banghak.stock.core.domain.trading.TradingFixtures.usd
import banghak.stock.core.domain.trading.TradingFixtures.user
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 판정 입력이 주문과 같은 사용자·시장·종목이고 충분히 신선한지, 같은 멱등 키가 다시 들어오지 않는지. */
class GuardrailInputIntegrityTest {
    private val now = kst("23:00")
    private val usMarketSell =
        OrderIntent(
            user,
            nvidia,
            OrderSide.SELL,
            OrderKind.MARKET,
            TimeInForce.DAY,
            null,
            Quantity.of("0.5"),
            null,
            OrderOrigin.MANUAL,
            manual,
            now,
        )

    @Test
    @DisplayName("다른 시장의 거래일·다른 사용자의 스냅샷·다른 종목의 현재가로는 컨텍스트를 만들지 않음")
    fun rejectsInputsOfAnotherMarketUserOrSymbol() {
        assertThatThrownBy { context(usMarketSell, now, tradingDay = GuardrailFixtures.krDay) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { context(usMarketSell, now, quote = quote(limitBuy(), "70000", now)) }
            .isInstanceOf(InvalidValueException::class.java)
        val otherUser = UserId.from(Ulid.of(now, ByteArray(10) { 7 }))
        val base = context(limitBuy(), kst("10:00"))
        assertThatThrownBy { base.copy(snapshot = base.snapshot.copy(userId = otherUser)) }
            .isInstanceOf(InvalidValueException::class.java)
        val othersOrder =
            brokerOrder(intent = limitBuy().copy(userId = otherUser), brokerOrderId = "X-1")
        assertThatThrownBy { context(limitBuy(), kst("10:00"), todayOrders = listOf(othersOrder)) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThat(samsung).isEqualTo(limitBuy().symbol)
    }

    @Test
    @DisplayName("고액 판정은 30초 넘은 현재가·10분 넘은 환율을 쓰지 않고 거부함")
    fun highValueNeedsFreshQuoteAndFx() {
        val rule = HighValueOrder()
        val freshQuote = quote(usMarketSell, "100.00", now.minusSeconds(30))
        val staleQuote = quote(usMarketSell, "100.00", now.minusSeconds(31))
        assertThat(rule.check(context(usMarketSell, now, quote = freshQuote)))
            .isEqualTo(GuardrailFinding.Clear)
        assertThat(rule.check(context(usMarketSell, now, quote = staleQuote)))
            .isInstanceOf(GuardrailFinding.Violation::class.java)
        val usLimit = limitBuy(symbol = nvidia, price = usd("100.00"), quantity = Quantity.of(715))
        assertThat(
                rule.check(context(usLimit, now, fx = usdKrw(now.minus(Duration.ofMinutes(10)))))
            )
            .isInstanceOf(GuardrailFinding.Note::class.java)
        assertThat(
                rule.check(
                    context(
                        usLimit,
                        now,
                        fx = usdKrw(now.minus(Duration.ofMinutes(10)).minusSeconds(1)),
                    )
                )
            )
            .isInstanceOf(GuardrailFinding.Violation::class.java)
    }

    @Test
    @DisplayName("같은 멱등 키의 주문이 오늘 이미 있으면 체결된 뒤라도 수동·자동 모두 거부함")
    fun rejectsSameClientOrderIdEvenAfterFill() {
        val rule = DuplicateIntent()
        val filledEarlier =
            brokerOrder(
                    intent = limitBuy(),
                    status = OrderStatus.FILLED,
                    filled = Quantity.of(10),
                    brokerOrderId = "B-9",
                )
                .copy(clientOrderId = key)
        val autoIntent = limitBuy(trigger = autoBuy, origin = OrderOrigin.AUTO_BUY)

        assertThat(
                rule.check(context(autoIntent, kst("10:00"), todayOrders = listOf(filledEarlier)))
            )
            .isInstanceOf(GuardrailFinding.Violation::class.java)
        assertThat(
                rule.check(context(limitBuy(), kst("10:00"), todayOrders = listOf(filledEarlier)))
            )
            .isInstanceOf(GuardrailFinding.Violation::class.java)
        assertThat(
                rule.check(
                    context(
                        autoIntent,
                        kst("10:00"),
                        todayOrders = listOf(filledEarlier),
                        clientOrderId = ClientOrderId("OTHER-KEY-2"),
                    )
                )
            )
            .isEqualTo(GuardrailFinding.Clear)
        assertThat(krw("1")).isNotNull()
    }
}
