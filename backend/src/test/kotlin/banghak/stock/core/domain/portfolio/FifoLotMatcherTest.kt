package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.portfolio.PortfolioFixtures.fx
import banghak.stock.core.domain.portfolio.PortfolioFixtures.lot
import banghak.stock.core.domain.portfolio.PortfolioFixtures.now
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.domain.trading.TradingFixtures.nvidia
import banghak.stock.core.domain.trading.TradingFixtures.samsung
import banghak.stock.core.domain.trading.TradingFixtures.usd
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FifoLotMatcherTest {
    private val older =
        lot(
            quantity = "10",
            unitCost = krw("60000"),
            boughtAt = now.minus(Duration.ofDays(10)),
            seed = 1,
        )
    private val newer =
        lot(
            quantity = "10",
            unitCost = krw("70000"),
            boughtAt = now.minus(Duration.ofDays(2)),
            seed = 2,
        )

    @Test
    @DisplayName("먼저 산 lot 부터 소진하고 비용은 수량 비례로 나누며 합이 정확히 맞음")
    fun disposesOldestFirstAndSplitsCosts() {
        val sale =
            Sale(
                "B-9",
                samsung,
                Quantity.of(15),
                krw("80000"),
                fee = krw("101"),
                tax = krw("300"),
                fxAtSell = null,
                executedAt = now,
            )

        val result = FifoLotMatcher.dispose(listOf(newer, older), sale)

        assertThat(result.disposals)
            .extracting<Quantity> { it.quantity }
            .containsExactly(Quantity.of(10), Quantity.of(5))
        assertThat(result.disposals[0].lotId).isEqualTo(older.id)
        assertThat(result.disposals.map { it.fee }.reduce(Money::plus)).isEqualTo(krw("101"))
        assertThat(result.disposals.map { it.tax }.reduce(Money::plus)).isEqualTo(krw("300"))
        assertThat(result.disposals[0].realized).isEqualTo(krw("199733"))
        assertThat(result.disposals[0].holdingDays).isEqualTo(10)
        assertThat(result.disposals[0].realizedKrw).isEqualTo(result.disposals[0].realized)
        assertThat(result.disposals[0].fxPnlKrw).isEqualTo(krw("0"))
        assertThat(result.lotsAfter.single { it.id == older.id }.isOpen).isFalse()
        assertThat(result.lotsAfter.single { it.id == newer.id }.remainingQuantity)
            .isEqualTo(Quantity.of(5))
        assertThatThrownBy { FifoLotMatcher.dispose(listOf(older), sale) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("해외 매도의 원화 실현손익 = 매매손익(외화 손익 × 매도 환율) + 환차손익(매입원가 × 환율 차)")
    fun foreignRealizedSplitsIntoTradeAndFxPnl() {
        val usLot =
            lot(
                symbol = nvidia,
                quantity = "3",
                unitCost = usd("100.00"),
                fxAtBuy = fx("1300"),
                boughtAt = now.minus(Duration.ofDays(40)),
            )
        val sale =
            Sale(
                "B-10",
                nvidia,
                Quantity.of(3),
                usd("110.00"),
                fee = usd("1.00"),
                tax = usd("0.50"),
                fxAtSell = fx("1400"),
                executedAt = now,
            )

        val disposal = FifoLotMatcher.dispose(listOf(usLot), sale).disposals.single()

        assertThat(disposal.realized).isEqualTo(usd("28.50"))
        assertThat(disposal.tradePnlKrw).isEqualTo(krw("39900"))
        assertThat(disposal.fxPnlKrw).isEqualTo(krw("30000"))
        assertThat(disposal.realizedKrw).isEqualTo(krw("69900"))
        assertThatThrownBy { FifoLotMatcher.dispose(listOf(usLot), sale.copy(fxAtSell = null)) }
            .isInstanceOf(InvalidValueException::class.java)
    }
}
