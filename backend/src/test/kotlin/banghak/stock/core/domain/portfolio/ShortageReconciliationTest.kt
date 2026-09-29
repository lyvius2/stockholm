package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.portfolio.ShortageReconciliation.Decision
import banghak.stock.core.domain.trading.FillIncrement
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradingFixtures
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ShortageReconciliationTest {
    private val samsung = TradingFixtures.samsung
    private val soldAt = Instant.parse("2026-09-30T01:00:00Z")

    @Test
    @DisplayName("보유가 기록보다 많으면 빠진 매수라 차이만큼 평균단가로 매도 직전에 채움")
    fun fillsMissingBuy() {
        // 기록 lot 5 + 대기열(매도 10) = -5, 보유 3 → 차이 8
        val decision =
            ShortageReconciliation.decide(
                holding(3, "65000"),
                Quantity.of(5),
                listOf(queued("S-1", OrderSide.SELL, 10, soldAt)),
            )

        assertThat(decision)
            .isEqualTo(
                Decision.FillGap(
                    Quantity.of(8),
                    TradingFixtures.krw("65000"),
                    soldAt.minusMillis(1),
                )
            )
    }

    @Test
    @DisplayName("보유에 없는데 판 것이 더 많으면 매입가를 몰라 사람이 확인함")
    fun unknownCostNeedsReview() {
        val decision =
            ShortageReconciliation.decide(
                null,
                Quantity.ZERO,
                listOf(queued("S-1", OrderSide.SELL, 10, soldAt)),
            )

        assertThat(decision).isInstanceOf(Decision.NeedsReview::class.java)
    }

    @Test
    @DisplayName("보유와 기록이 맞고 매도 뒤에 대기열 매수가 있으면 순서 문제라 그 매수를 먼저 반영함")
    fun laterBuyFirstWhenConsistent() {
        // 기록 0 + 대기열(매도 5, 매수 5) = 0, 보유 0 → 차이 0
        val decision =
            ShortageReconciliation.decide(
                null,
                Quantity.ZERO,
                listOf(
                    queued("S-1", OrderSide.SELL, 5, soldAt),
                    queued("B-1", OrderSide.BUY, 5, soldAt.plusSeconds(2)),
                ),
            )

        assertThat(decision).isEqualTo(Decision.ApplyBuysFirst(listOf("B-1")))
    }

    @Test
    @DisplayName("기록이 보유보다 많으면(빠진 매도) 사람이 확인함")
    fun excessRecordsNeedReview() {
        // 기록 lot 2 + 대기열(매도 3, 매수 5) = 4, 보유 1 → 차이 -3
        val decision =
            ShortageReconciliation.decide(
                holding(1, "65000"),
                Quantity.of(2),
                listOf(
                    queued("S-1", OrderSide.SELL, 3, soldAt),
                    queued("B-1", OrderSide.BUY, 5, soldAt.plusSeconds(2)),
                ),
            )

        assertThat(decision).isInstanceOf(Decision.NeedsReview::class.java)
    }

    private fun queued(id: String, side: OrderSide, qty: Long, at: Instant) =
        QueuedFill(
            id,
            FillIncrement(
                TradingFixtures.user,
                "O-$id",
                samsung,
                side,
                Quantity.of(qty),
                Money.of(qty * 70000, Currency.KRW),
                Money.zero(Currency.KRW),
                Money.zero(Currency.KRW),
                OrderOrigin.MANUAL,
                at,
            ),
            if (side == OrderSide.SELL) FillState.BLOCKED else FillState.PENDING,
            null,
        )

    private fun holding(qty: Long, avg: String): BrokerHolding {
        val price = TradingFixtures.krw(avg)
        val cost = price.times(Quantity.of(qty))
        return BrokerHolding(
            samsung,
            Quantity.of(qty),
            price,
            price,
            cost,
            cost,
            Money.zero(Currency.KRW),
        )
    }
}
