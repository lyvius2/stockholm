package banghak.stock.engine.application.trading

import banghak.stock.core.domain.error.BrokerAccessDeniedException
import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Percent
import banghak.stock.core.domain.trading.CommissionRate
import banghak.stock.core.domain.trading.PriceLimits
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.FakeMarketData
import banghak.stock.support.fakes.FakeTradingPort
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class OrderTicketServiceTest {
    // KST 2026-10-01 00:30 (UTC 로는 아직 9월 30일)
    private val clock = MutableClock(Instant.parse("2026-09-30T15:30:00Z"))
    private val user = TradingFixtures.user
    private val samsung = TradingFixtures.samsung
    private val trading = FakeTradingPort()
    private val marketData = FakeMarketData()
    private val service = OrderTicketService(trading, marketData, clock)
    private val limits = PriceLimits(samsung, krw("91000"), krw("49000"), clock.instant())

    @Test
    @DisplayName("매수 가능 금액·판매 가능 수량·상하한가와 오늘(KST) 적용되는 그 시장 수수료율을 모음")
    fun gathersTicket() {
        trading.buyingPower[Currency.KRW] = krw("1000000")
        trading.sellable[samsung] = Quantity.of(7)
        marketData.priceLimits[samsung] = limits
        trading.commissions +=
            listOf(
                rate(Market.US, "0.001", null, null),
                rate(Market.KR, "0.0001", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)),
                rate(Market.KR, "0.00015", LocalDate.of(2026, 10, 1), null),
            )

        val ticket = service.ticket(user, samsung)

        assertThat(ticket.buyingPower).isEqualTo(krw("1000000"))
        assertThat(ticket.sellableQuantity).isEqualTo(Quantity.of(7))
        assertThat(ticket.priceLimits).isEqualTo(limits)
        assertThat(ticket.commissionRate).isEqualTo(Percent.ofRatio("0.00015"))
    }

    @Test
    @DisplayName("상하한가·수수료율을 받지 못해도 나머지는 돌려줌")
    fun referenceInfoIsOptional() {
        trading.commissionFailure = BrokerUnavailableException("토스에 연결할 수 없음")

        val ticket = service.ticket(user, samsung)

        assertThat(ticket.priceLimits).isNull()
        assertThat(ticket.commissionRate).isNull()
        assertThat(ticket.sellableQuantity).isEqualTo(Quantity.ZERO)
    }

    @Test
    @DisplayName("참고 정보 조회가 인증 거부(401·403)여도 비워 두고 필수 정보는 돌려줌")
    fun accessDeniedOnReferenceInfoIsBlank() {
        trading.sellable[samsung] = Quantity.of(7)
        marketData.priceLimitsFailure = BrokerAccessDeniedException("토스가 접속을 거부함")
        trading.commissionFailure = BrokerAccessDeniedException("토스가 조회를 거부함")

        val ticket = service.ticket(user, samsung)

        assertThat(ticket.sellableQuantity).isEqualTo(Quantity.of(7))
        assertThat(ticket.priceLimits).isNull()
        assertThat(ticket.commissionRate).isNull()
    }

    @Test
    @DisplayName("필수 정보 조회가 인증 거부면 예외를 그대로 올려 키·허용 IP 문제를 드러냄")
    fun accessDeniedOnRequiredInfoPropagates() {
        trading.accountFailure = BrokerAccessDeniedException("토스가 조회를 거부함")

        assertThatThrownBy { service.ticket(user, samsung) }
            .isInstanceOf(BrokerAccessDeniedException::class.java)
    }

    @Test
    @DisplayName("계좌 정보를 받지 못하면 예외를 그대로 올림")
    fun accountFailurePropagates() {
        trading.accountFailure = BrokerUnavailableException("토스에 연결할 수 없음")

        assertThatThrownBy { service.ticket(user, samsung) }
            .isInstanceOf(BrokerUnavailableException::class.java)
    }

    private fun rate(market: Market, ratio: String, start: LocalDate?, end: LocalDate?) =
        CommissionRate(market, Percent.ofRatio(ratio), start, end)
}
