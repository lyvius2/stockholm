package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.portfolio.PortfolioFixtures.fx
import banghak.stock.core.domain.portfolio.PortfolioFixtures.lot
import banghak.stock.core.domain.portfolio.PortfolioFixtures.now
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.domain.trading.TradingFixtures.nvidia
import banghak.stock.core.domain.trading.TradingFixtures.samsung
import banghak.stock.core.domain.trading.TradingFixtures.usd
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class LotAndPositionTest {
    private val window = Duration.ofHours(168)

    @Test
    @DisplayName("해외 lot 은 USD→KRW 매수 시점 환율이 있어야 하고 원가는 그 환율로 환산함")
    fun foreignLotRequiresFxAndConvertsCost() {
        assertThatThrownBy { lot(symbol = nvidia, unitCost = usd("100.00")) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { lot(symbol = nvidia, unitCost = krw("100"), fxAtBuy = fx("1400")) }
            .isInstanceOf(InvalidValueException::class.java)
        val us =
            lot(symbol = nvidia, quantity = "2.5", unitCost = usd("100.00"), fxAtBuy = fx("1400.5"))
        assertThat(us.costBasis()).isEqualTo(usd("250.00"))
        assertThat(us.costBasisInKrw()).isEqualTo(krw("350125"))
        assertThat(lot().costBasisInKrw()).isEqualTo(krw("700000"))
    }

    @Test
    @DisplayName("부분 매도는 잔여 수량만 줄이고 정확히 168시간이면 창을 지난 것으로 봄")
    fun sellingReducesRemainingAndWindowBoundary() {
        val after = lot().afterSelling(Quantity.of(4))
        assertThat(after.remainingQuantity).isEqualTo(Quantity.of(6))
        assertThat(after.boughtQuantity).isEqualTo(Quantity.of(10))
        assertThat(after.afterSelling(Quantity.of(6)).isOpen).isFalse()
        assertThatThrownBy { after.afterSelling(Quantity.of(7)) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThat(lot().isHeldAtLeast(window, now.plus(window))).isTrue()
        assertThat(lot().isHeldAtLeast(window, now.plus(window).minusSeconds(1))).isFalse()
    }

    @Test
    @DisplayName("다른 사용자의 lot 이 섞이면 포지션을 만들지 않음")
    fun rejectsLotsOfAnotherUser() {
        val other = lot(seed = 9).copy(userId = UserId.from(Ulid.of(now, ByteArray(10) { 7 })))

        assertThatThrownBy { Position(TradingFixtures.user, samsung, listOf(lot(), other)) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { Position.fromLots(TradingFixtures.user, listOf(lot(), other)) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("포지션 수량·평균 단가·출처별 수량은 열린 lot 에서 계산함")
    fun positionAggregatesOpenLots() {
        val position =
            Position(
                TradingFixtures.user,
                samsung,
                listOf(
                    lot(
                        quantity = "10",
                        unitCost = krw("70000"),
                        origin = BuyOrigin.MANUAL,
                        seed = 1,
                    ),
                    lot(
                        quantity = "20",
                        remaining = "5",
                        unitCost = krw("64000"),
                        origin = BuyOrigin.AUTO_BUY,
                        seed = 2,
                    ),
                ),
            )
        assertThat(position.quantity()).isEqualTo(Quantity.of(15))
        assertThat(position.averageCost()).isEqualTo(krw("68000"))
        assertThat(position.quantityFrom(BuyOrigin.AUTO_BUY)).isEqualTo(Quantity.of(5))
        assertThat(position.marketValue(krw("80000"))).isEqualTo(krw("1200000"))
        assertThat(Position(TradingFixtures.user, samsung, emptyList()).averageCost())
            .isEqualTo(krw("0"))
        assertThatThrownBy { Position(TradingFixtures.user, samsung, listOf(lot(remaining = "0"))) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThat(
                Position.fromLots(
                    TradingFixtures.user,
                    listOf(
                        lot(seed = 1),
                        lot(remaining = "0", seed = 2),
                        lot(
                            symbol = nvidia,
                            unitCost = usd("1.00"),
                            fxAtBuy = fx("1400"),
                            seed = 3,
                        ),
                    ),
                )
            )
            .extracting<Int> { it.openLots.size }
            .containsExactlyInAnyOrder(1, 1)
    }
}
