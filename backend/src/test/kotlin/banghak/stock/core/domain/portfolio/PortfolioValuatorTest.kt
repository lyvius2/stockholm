package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.money.Percent
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.domain.trading.TradingFixtures.usd
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PortfolioValuatorTest {
    private val samsung = TradingFixtures.samsung
    private val nvidia = TradingFixtures.nvidia
    private val now = TradingFixtures.now
    private val fx = ExchangeRate(Currency.USD, Currency.KRW, BigDecimal("1400"), now)

    @Test
    @DisplayName("현재가가 있으면 그것으로, 없으면 증권사 마지막 가격으로 평가하고 손익·수익률·오늘 변동을 냄")
    fun valuesWithQuotesOrBrokerPrice() {
        val valuation =
            valuate(
                holdings = listOf(holding(samsung, 10, avg = "65000", last = "70000")),
                quotes = mapOf(samsung to krw("71000")),
                previousCloses = mapOf(samsung to krw("69000")),
            )

        val kr = valuation.byMarket.getValue(Market.KR)
        assertThat(kr.marketValue).isEqualTo(krw("710000"))
        assertThat(kr.purchaseAmount).isEqualTo(krw("650000"))
        assertThat(kr.profitLoss).isEqualTo(krw("60000"))
        // 60000 / 650000
        assertThat(kr.returnRate).isEqualTo(Percent.ofRatio("0.092308"))
        assertThat(kr.todayChange).isEqualTo(krw("20000"))
        assertThat(kr.risers).isEqualTo(1)

        val fallback =
            valuate(listOf(holding(samsung, 10, "65000", "70000")), emptyMap(), emptyMap())
        assertThat(fallback.byMarket.getValue(Market.KR).marketValue).isEqualTo(krw("700000"))
        assertThat(fallback.byMarket.getValue(Market.KR).todayChange).isNull()
    }

    @Test
    @DisplayName("전체는 국내 + 미국 × 환율이고, 미국 보유가 있는데 환율이 없으면 전체를 내지 않음")
    fun totalsInKrwOnlyWithRate() {
        val holdings =
            listOf(
                holding(samsung, 10, "65000", "70000"),
                holding(nvidia, 2, "100.00", "120.50"),
            )

        val converted = valuate(holdings, emptyMap(), emptyMap(), fx = fx)
        // 700000 + 2 × 120.50 × 1400 = 700000 + 337400
        assertThat(converted.totalMarketValueKrw).isEqualTo(krw("1037400"))
        // 손익: 50000 + (241.00 − 200.00) × 1400 = 50000 + 57400
        assertThat(converted.totalProfitLossKrw).isEqualTo(krw("107400"))
        assertThat(converted.byMarket.getValue(Market.US).marketValue).isEqualTo(usd("241.00"))

        val noRate = valuate(holdings, emptyMap(), emptyMap(), fx = null)
        assertThat(noRate.totalMarketValueKrw).isNull()
        assertThat(noRate.totalReturnRate).isNull()
        assertThat(noRate.byMarket.getValue(Market.US).marketValue).isEqualTo(usd("241.00"))

        val koreanOnly =
            valuate(listOf(holding(samsung, 10, "65000", "70000")), emptyMap(), emptyMap())
        assertThat(koreanOnly.totalMarketValueKrw).isEqualTo(krw("700000"))
    }

    @Test
    @DisplayName("매수금액이 0이면 수익률이 없고, 오른·내린 종목 수는 전일 종가를 아는 종목만 셈")
    fun zeroCostAndCounts() {
        val free = holding(samsung, 10, avg = "0", last = "70000").copy(purchaseAmount = krw("0"))
        val hynix = Symbol(Market.KR, "000660")

        val freeOnly = valuate(listOf(free), emptyMap(), emptyMap())
        assertThat(freeOnly.byMarket.getValue(Market.KR).returnRate).isNull()
        assertThat(freeOnly.totalReturnRate).isNull()

        val valuation =
            valuate(
                listOf(free, holding(hynix, 1, "180000", "180000")),
                emptyMap(),
                previousCloses = mapOf(hynix to krw("181000")),
            )

        val kr = valuation.byMarket.getValue(Market.KR)
        assertThat(kr.risers).isZero()
        assertThat(kr.fallers).isEqualTo(1)
        // 한 종목이라도 전일 종가를 모르면 시장의 오늘 변동은 내지 않음
        assertThat(kr.todayChange).isNull()
    }

    @Test
    @DisplayName("환산 환율은 USD→KRW 만 받음")
    fun rejectsOtherRates() {
        assertThatThrownBy {
                valuate(
                    emptyList(),
                    emptyMap(),
                    emptyMap(),
                    fx = ExchangeRate(Currency.KRW, Currency.USD, BigDecimal("0.0007"), now),
                )
            }
            .isInstanceOf(InvalidValueException::class.java)
    }

    private fun valuate(
        holdings: List<BrokerHolding>,
        quotes: Map<Symbol, Money>,
        previousCloses: Map<Symbol, Money>,
        fx: ExchangeRate? = null,
    ) =
        PortfolioValuator.valuate(
            TradingFixtures.user,
            holdings,
            quotes,
            previousCloses,
            fx,
            cashBuyingPower = emptyMap(),
            isDelayed = false,
            asOf = now,
        )

    private fun holding(symbol: Symbol, quantity: Long, avg: String, last: String): BrokerHolding {
        val currency = symbol.market.currency
        val average = Money.of(avg, currency)
        val lastPrice = Money.of(last, currency)
        val shares = Quantity.of(quantity)
        return BrokerHolding(
            symbol,
            shares,
            average,
            lastPrice,
            average.times(shares),
            lastPrice.times(shares),
            lastPrice.times(shares).minus(average.times(shares)),
        )
    }
}
