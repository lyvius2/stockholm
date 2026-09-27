package banghak.stock.core.domain.trading

import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.domain.trading.TradingFixtures.nvidia
import banghak.stock.core.domain.trading.TradingFixtures.samsung
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class StartStockResolverTest {
    private val hynix = Symbol(Market.KR, "000660")
    private val apple = Symbol(Market.US, "AAPL")

    @Test
    @DisplayName("직전 종목이 상장 중이면 그것을 고름")
    fun prefersLastViewedWhenListed() {
        val result =
            StartStockResolver.resolve(
                StartStockCandidates(
                    hynix,
                    true,
                    listOf(PositionValuation(samsung, krw("1"), krw("1"))),
                    Market.KR,
                )
            )
        assertThat(result).isEqualTo(StartStock(hynix, StartStockReason.LAST_VIEWED))
    }

    @Test
    @DisplayName("직전 종목이 없거나 상장폐지면 평가금액이 가장 큰 보유 종목, 같으면 매입원가·코드 순")
    fun fallsBackToLargestPosition() {
        val valuations =
            listOf(
                PositionValuation(samsung, krw("1000000"), krw("900000")),
                PositionValuation(apple, krw("1000000"), krw("950000")),
                PositionValuation(hynix, krw("1000000"), krw("950000")),
            )
        assertThat(
                StartStockResolver.resolve(
                    StartStockCandidates(nvidia, false, valuations, Market.KR)
                )
            )
            .isEqualTo(StartStock(hynix, StartStockReason.LARGEST_POSITION))
        assertThat(
                StartStockResolver.resolve(
                        StartStockCandidates(
                            null,
                            false,
                            listOf(
                                PositionValuation(apple, krw("2"), krw("1")),
                                PositionValuation(samsung, krw("3"), krw("1")),
                            ),
                            Market.US,
                        )
                    )
                    .symbol
            )
            .isEqualTo(samsung)
    }

    @Test
    @DisplayName("보유 조회를 못 받았거나 보유가 없으면 기본 시장의 기본 종목")
    fun fallsBackToDefaultSymbol() {
        assertThat(StartStockResolver.resolve(StartStockCandidates(null, false, null, Market.KR)))
            .isEqualTo(StartStock(Symbol.DEFAULT_KR, StartStockReason.DEFAULT))
        assertThat(
                StartStockResolver.resolve(
                    StartStockCandidates(null, false, emptyList(), Market.US)
                )
            )
            .isEqualTo(StartStock(Symbol.DEFAULT_US, StartStockReason.DEFAULT))
    }
}
