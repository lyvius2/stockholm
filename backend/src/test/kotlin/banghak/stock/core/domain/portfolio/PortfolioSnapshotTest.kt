package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.portfolio.PortfolioFixtures.lot
import banghak.stock.core.domain.portfolio.PortfolioFixtures.now
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.domain.trading.TradingFixtures.usd
import banghak.stock.core.domain.trading.TradingFixtures.user
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PortfolioSnapshotTest {
    private val deposit = DepositBalance(mapOf(Currency.KRW to krw("1000000")), now)

    @Test
    @DisplayName("정확히 maxAge 만큼 지난 스냅샷은 쓸 수 있고 1초 더 지나면 stale 임")
    fun staleBoundary() {
        val snapshot =
            PortfolioSnapshot(user, Market.KR, emptyList(), deposit, emptyList(), emptyList(), now)
        val maxAge = Duration.ofSeconds(10)
        assertThat(snapshot.isStale(now.plus(maxAge), maxAge)).isFalse()
        assertThat(snapshot.isStale(now.plus(maxAge).plusSeconds(1), maxAge)).isTrue()
    }

    @Test
    @DisplayName("시장이 다른 포지션은 섞을 수 없고 통화 칸과 금액 통화는 같아야 함")
    fun marketAndCurrencyConsistency() {
        assertThatThrownBy {
                PortfolioSnapshot(
                    user,
                    Market.US,
                    listOf(Position(lot().symbol, listOf(lot()))),
                    deposit,
                    emptyList(),
                    emptyList(),
                    now,
                )
            }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { DepositBalance(mapOf(Currency.KRW to usd("1.00")), now) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThat(deposit.available(Currency.USD)).isEqualTo(usd("0.00"))
    }
}
