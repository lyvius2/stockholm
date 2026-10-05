package banghak.stock.engine.adapter.`in`.web

import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Percent
import banghak.stock.core.domain.portfolio.HoldingValuation
import banghak.stock.core.domain.portfolio.MarketValuation
import banghak.stock.core.domain.portfolio.PortfolioValuation
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.domain.trading.TradingFixtures.usd
import banghak.stock.core.usecase.LookupPortfolioValuationUseCase
import banghak.stock.engine.adapter.`in`.web.portfolio.PortfolioController
import banghak.stock.support.web.ApiTestSupport
import banghak.stock.support.web.ApiTestSupport.assertConforms
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 평가금액 REST 는 세션 사용자의 평가만 묻고 응답이 스키마에 맞음. */
class PortfolioApiTest {
    private val asked = mutableListOf<UserId>()
    private var valuation: () -> PortfolioValuation = { sample() }
    private val valuations =
        object : LookupPortfolioValuationUseCase {
            override fun valuation(userId: UserId): PortfolioValuation {
                asked += userId
                return valuation()
            }
        }
    private val mvc = ApiTestSupport.mockMvc(PortfolioController(valuations))

    @Test
    @DisplayName("세션 사용자의 평가를 시장별·전체 원화·매수 가능 현금까지 돌려줌")
    fun valuationOfSessionUser() {
        val body =
            mvc.perform(get("/portfolio/valuation"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.byMarket[0].market").value("KR"))
                .andExpect(jsonPath("$.byMarket[0].holdings[0].quantity").value("10"))
                .andExpect(jsonPath("$.byMarket[1].returnRate").value(null))
                .andExpect(jsonPath("$.totalMarketValueKrw.amount").value("1050000"))
                .andExpect(jsonPath("$.fxUsdKrw.rate").value("1400"))
                .andExpect(jsonPath("$.cashBuyingPower.USD.amount").value("100.00"))
                .andExpect(jsonPath("$.isDelayed").value(true))
                .andReturn()
                .response
                .contentAsString

        assertThat(asked).containsExactly(TradingFixtures.user)
        assertConforms(body, "api-portfolio-valuation")
    }

    @Test
    @DisplayName("보유를 받지 못하면 503")
    fun brokerUnavailable() {
        valuation = { throw BrokerUnavailableException("토스에 닿지 않음") }

        mvc.perform(get("/portfolio/valuation"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("BrokerUnavailableException"))
    }

    private fun sample(): PortfolioValuation {
        val asOf = Instant.parse("2026-10-05T01:00:00Z")
        val samsung =
            HoldingValuation(
                symbol = TradingFixtures.samsung,
                quantity = Quantity.of(10),
                lastPrice = krw("70000"),
                purchaseAmount = krw("650000"),
                marketValue = krw("700000"),
                profitLoss = krw("50000"),
                previousClose = krw("69000"),
                todayChange = krw("10000"),
            )
        val nvidia =
            HoldingValuation(
                symbol = TradingFixtures.nvidia,
                quantity = Quantity.of(2),
                lastPrice = usd("125.00"),
                purchaseAmount = usd("0"),
                marketValue = usd("250.00"),
                profitLoss = usd("250.00"),
                previousClose = null,
                todayChange = null,
            )
        return PortfolioValuation(
            userId = TradingFixtures.user,
            byMarket =
                mapOf(
                    Market.KR to
                        MarketValuation(
                            Market.KR,
                            krw("700000"),
                            krw("650000"),
                            krw("50000"),
                            Percent.ofRatio("0.0769"),
                            krw("10000"),
                            1,
                            0,
                            listOf(samsung),
                        ),
                    Market.US to
                        MarketValuation(
                            Market.US,
                            usd("250.00"),
                            usd("0"),
                            usd("250.00"),
                            null,
                            null,
                            0,
                            0,
                            listOf(nvidia),
                        ),
                ),
            totalMarketValueKrw = krw("1050000"),
            totalProfitLossKrw = krw("400000"),
            totalReturnRate = Percent.ofRatio("0.6154"),
            fxUsdKrw = ExchangeRate(Currency.USD, Currency.KRW, BigDecimal("1400"), asOf),
            cashBuyingPower = mapOf(Currency.KRW to krw("300000"), Currency.USD to usd("100.00")),
            isDelayed = true,
            asOf = asOf,
        )
    }
}
