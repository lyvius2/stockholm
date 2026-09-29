package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.FillIncrement
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradingFixtures
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class OpeningLedgerTest {
    private val samsung = TradingFixtures.samsung
    private val hynix = Symbol(Market.KR, "000660")
    private val at = Instant.parse("2026-09-30T01:00:00Z")

    @Test
    @DisplayName("보유 조회가 방금 체결을 아직 반영하지 않았으면(빈 보유) 기초 lot 없이 대기열 체결을 반영함")
    fun holdingsLagKeepsQueuedFill() {
        val plan =
            OpeningLedger.plan(emptyList(), listOf(queued("F-1", OrderSide.BUY, samsung, 10)))

        assertThat(plan.positions).isEmpty()
        assertThat(plan.skippedFillIds).isEmpty()
    }

    @Test
    @DisplayName("보유가 대기열 매수를 이미 담고 있으면 그만큼 빼고 나머지만 기초 lot 으로 만듦")
    fun holdingsIncludingQueuedBuy() {
        val plan =
            OpeningLedger.plan(
                listOf(holding(samsung, 30, "65000")),
                listOf(queued("F-1", OrderSide.BUY, samsung, 10)),
            )

        assertThat(plan.positions)
            .containsExactly(OpeningPosition(samsung, Quantity.of(20), krw("65000")))
    }

    @Test
    @DisplayName("대기열 매도는 기초 수량에 더함: 보유 15주에 대기열 매도 5주면 기초 20주에서 5주를 파는 것임")
    fun queuedSellAddsBack() {
        val plan =
            OpeningLedger.plan(
                listOf(holding(samsung, 15, "65000")),
                listOf(queued("F-1", OrderSide.SELL, samsung, 5)),
            )

        assertThat(plan.positions)
            .containsExactly(OpeningPosition(samsung, Quantity.of(20), krw("65000")))
    }

    @Test
    @DisplayName("보유에 없는데 판 것이 더 많은 종목은 매입가를 몰라 그 종목의 대기열 체결을 모두 건너뜀")
    fun unknownCostHistoryIsSkipped() {
        val plan =
            OpeningLedger.plan(
                emptyList(),
                listOf(
                    queued("F-1", OrderSide.BUY, hynix, 3),
                    queued("F-2", OrderSide.SELL, hynix, 10),
                    queued("F-3", OrderSide.BUY, samsung, 5),
                    queued("F-4", OrderSide.SELL, samsung, 5),
                ),
            )

        assertThat(plan.positions).isEmpty()
        assertThat(plan.skippedFillIds).containsExactlyInAnyOrder("F-1", "F-2")
    }

    private fun queued(id: String, side: OrderSide, symbol: Symbol, qty: Long) =
        QueuedFill(
            id,
            FillIncrement(
                TradingFixtures.user,
                "B-$id",
                symbol,
                side,
                Quantity.of(qty),
                Money.of(qty * 70000, Currency.KRW),
                Money.zero(Currency.KRW),
                Money.zero(Currency.KRW),
                OrderOrigin.MANUAL,
                at,
            ),
            FillState.PENDING,
            null,
        )

    private fun holding(symbol: Symbol, qty: Long, avg: String): BrokerHolding {
        val price = krw(avg)
        val cost = price.times(Quantity.of(qty))
        return BrokerHolding(
            symbol,
            Quantity.of(qty),
            price,
            price,
            cost,
            cost,
            Money.zero(Currency.KRW),
        )
    }

    private fun krw(amount: String) = TradingFixtures.krw(amount)
}
