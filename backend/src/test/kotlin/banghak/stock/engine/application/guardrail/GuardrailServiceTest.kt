package banghak.stock.engine.application.guardrail

import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.guardrail.GuardrailFixtures
import banghak.stock.core.domain.guardrail.GuardrailVerdict
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.trading.ManualAmendTrigger
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.FakeMarketCalendar
import banghak.stock.support.fakes.FakeMarketData
import banghak.stock.support.fakes.FakeTradingPort
import banghak.stock.support.fakes.MemoryBrokerOrderStore
import banghak.stock.support.fakes.MemoryLotStore
import java.math.BigDecimal
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 가드레일 서비스가 증권사·로컬 기록에서 컨텍스트를 바르게 모으는지 규칙 판정으로 확인함. */
class GuardrailServiceTest {
    private val clock = MutableClock(GuardrailFixtures.kst("10:00"))
    private val trading = FakeTradingPort()
    private val marketData = FakeMarketData()
    private val calendar =
        FakeMarketCalendar(
            mapOf(Market.KR to GuardrailFixtures.krDay, Market.US to GuardrailFixtures.usDay)
        )
    private val orders = MemoryBrokerOrderStore()
    private val service =
        GuardrailService(trading, marketData, calendar, MemoryLotStore(), orders, clock)
    private val buy = TradingFixtures.limitBuy()

    @Test
    @DisplayName("정규장의 국내 지정가 매수는 통과하고, 거래일은 KST 날짜로 물음")
    fun passesRegularSessionLimitBuy() {
        val verdict = service.evaluate(buy, GuardrailFixtures.key)

        assertThat(verdict).isEqualTo(GuardrailVerdict.Passed(emptyList()))
        assertThat(calendar.requestedDates).containsExactly(Market.KR to LocalDate.of(2026, 9, 30))
    }

    @Test
    @DisplayName("토스 앱에서 낸 반대 방향 미체결도 증권사 미체결 목록으로 잡아 거부함")
    fun seesOpenOrdersPlacedOutsideStockholm() {
        trading.openOrders +=
            TradingFixtures.brokerRecord(
                intent = buy.copy(side = OrderSide.SELL),
                brokerOrderId = "APP-1",
            )

        val verdict = service.evaluate(buy, GuardrailFixtures.key) as GuardrailVerdict.Rejected

        assertThat(verdict.violations.map { it.rule }).containsExactly("OppositeSideOpenOrder")
    }

    @Test
    @DisplayName("정정할 원주문은 같은 방향 미체결로 세지 않지만, 다른 같은 방향 미체결은 여전히 노트를 남김")
    fun amendmentDoesNotCountItsOwnOriginal() {
        val amend = buy.copy(trigger = ManualAmendTrigger(TradingFixtures.device, "B-1"))
        trading.openOrders += TradingFixtures.brokerRecord(intent = buy, brokerOrderId = "B-1")

        assertThat(service.evaluate(amend, GuardrailFixtures.key))
            .isEqualTo(GuardrailVerdict.Passed(emptyList()))

        trading.openOrders += TradingFixtures.brokerRecord(intent = buy, brokerOrderId = "B-2")
        assertThat(service.evaluate(amend, GuardrailFixtures.key).notes.map { it.rule })
            .containsExactly("DuplicateIntent")
    }

    @Test
    @DisplayName("오늘 로컬에 기록된 주문과 멱등 키가 같으면 거부함")
    fun rejectsKeyAlreadyUsedToday() {
        val earlier =
            TradingFixtures.brokerOrder(intent = buy)
                .copy(updatedAt = GuardrailFixtures.kst("09:10"))
        orders.recordAccepted(earlier, isHighValueConfirmed = false)

        val verdict = service.evaluate(buy, earlier.clientOrderId) as GuardrailVerdict.Rejected

        assertThat(verdict.violations.map { it.rule }).containsExactly("DuplicateIntent")
    }

    @Test
    @DisplayName("미국 주문은 환율을 받지 못하면 금액을 판정할 수 없어 거부함")
    fun usOrderWithoutFxIsRejected() {
        clock.moveTo(GuardrailFixtures.kst("23:00"))
        val usBuy =
            TradingFixtures.limitBuy(
                symbol = TradingFixtures.nvidia,
                price = TradingFixtures.usd("100"),
            )

        val withoutFx = service.evaluate(usBuy, GuardrailFixtures.key) as GuardrailVerdict.Rejected
        marketData.rates[Currency.USD to Currency.KRW] =
            ExchangeRate(Currency.USD, Currency.KRW, BigDecimal("1400"), clock.instant())
        val withFx = service.evaluate(usBuy, GuardrailFixtures.key)

        assertThat(withoutFx.violations.map { it.rule }).containsExactly("HighValueOrder")
        assertThat(withFx.isPassed).isTrue()
    }

    @Test
    @DisplayName("계좌를 받지 못하면 판정하지 않고 예외를 올림(주문하지 않음)")
    fun accountFailurePropagates() {
        trading.accountFailure = BrokerUnavailableException("토스 연결 실패")

        assertThatThrownBy { service.evaluate(buy, GuardrailFixtures.key) }
            .isInstanceOf(BrokerUnavailableException::class.java)
    }

    @Test
    @DisplayName("자동 주문은 한도 규칙이 붙기 전까지 모두 거부함")
    fun automaticOrdersAreRejectedForNow() {
        val automatic =
            TradingFixtures.limitBuy(
                trigger = TradingFixtures.autoBuy,
                origin = OrderOrigin.AUTO_BUY,
            )

        val verdict =
            service.evaluate(automatic, GuardrailFixtures.key) as GuardrailVerdict.Rejected

        assertThat(verdict.violations.map { it.rule }).containsExactly("AutomaticOrderRules")
    }
}
