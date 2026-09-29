package banghak.stock.engine.application.portfolio

import banghak.stock.core.domain.account.Device
import banghak.stock.core.domain.eventlog.LotClosed
import banghak.stock.core.domain.eventlog.LotReduced
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.portfolio.BrokerHolding
import banghak.stock.core.domain.portfolio.BuyOrigin
import banghak.stock.core.domain.portfolio.FillState
import banghak.stock.core.domain.trading.FillIncrement
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.shared.crypto.UlidGenerator
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.FakeMarketData
import banghak.stock.support.fakes.FakeTradingPort
import banghak.stock.support.fakes.MemoryDevicePort
import banghak.stock.support.fakes.MemoryEventStore
import banghak.stock.support.fakes.MemoryFillQueue
import banghak.stock.support.fakes.MemoryLotLedger
import banghak.stock.support.fakes.MemoryLotStore
import banghak.stock.support.fakes.MemoryUserAccountPort
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LotLedgerServiceTest {
    private val clock = MutableClock(Instant.parse("2026-09-30T01:00:00Z"))
    private val user = TradingFixtures.user
    private val samsung = TradingFixtures.samsung
    private val fills = MemoryFillQueue()
    private val ledger = MemoryLotLedger()
    private val lots = MemoryLotStore()
    private val trading = FakeTradingPort()
    private val marketData = FakeMarketData()
    private val users = MemoryUserAccountPort()
    private val devices = MemoryDevicePort()
    private val events = MemoryEventStore()
    private val service =
        LotLedgerService(
            fills,
            ledger,
            lots,
            trading,
            marketData,
            users,
            devices,
            LotJournal(lots, ledger, fills, events, clock),
            UlidGenerator(clock),
            clock,
        )

    @BeforeEach
    fun setUp() {
        users.save(TradingFixtures.account())
        devices.save(Device(TradingFixtures.device, "pk", "this-mac", null, clock.instant(), null))
    }

    @Nested
    @DisplayName("원장 시작")
    inner class Start {
        @Test
        @DisplayName("체결이 없으면 원장을 시작하지 않음")
        fun doesNothingWithoutFills() {
            service.processFills()

            assertThat(ledger.started).isEmpty()
        }

        @Test
        @DisplayName("보유가 없으면 빈 원장으로 시작하고, 원장 시작 뒤 매수는 lot 이 됨")
        fun emptyHoldingsStartEmptyLedger() {
            enqueue(buy("B-1", qty = 10, amount = "700000", at = clock.instant()))

            service.processFills()

            assertThat(ledger.started[user]).isEqualTo(clock.instant())
            assertThat(lots.lots.map { it.isOpening }).containsExactly(false)
        }

        @Test
        @DisplayName("보유가 대기열 매수를 이미 담고 있으면 그만큼 빼고 기초 lot 을 만들며, 그 매수는 따로 lot 이 됨")
        fun openingLotsFromHoldings() {
            trading.holdings += holding(samsung, qty = 30, avg = "65000")
            enqueue(buy("B-1", qty = 5, amount = "325000", at = clock.instant().minusSeconds(60)))

            service.processFills()

            val opening = lots.lots.single { it.isOpening }
            assertThat(opening.remainingQuantity).isEqualTo(Quantity.of(25))
            assertThat(opening.unitCost).isEqualTo(TradingFixtures.krw("65000"))
            assertThat(opening.origin).isEqualTo(BuyOrigin.MANUAL)
            assertThat(opening.boughtAt).isBefore(clock.instant().minusSeconds(60))
            assertThat(lots.lots.single { !it.isOpening }.remainingQuantity)
                .isEqualTo(Quantity.of(5))
            assertThat(fills.stateOf("B-1")).isEqualTo(FillState.DONE)
        }

        @Test
        @DisplayName("보유 조회가 방금 체결을 아직 반영하지 않아도(빈 보유) 그 체결은 건너뛰지 않고 lot 이 됨")
        fun holdingsLagDoesNotLoseFill() {
            enqueue(buy("B-1", qty = 10, amount = "700000", at = clock.instant().minusSeconds(5)))

            service.processFills()

            assertThat(lots.lots.map { it.remainingQuantity to it.isOpening })
                .containsExactly(Quantity.of(10) to false)
            assertThat(fills.stateOf("B-1")).isEqualTo(FillState.DONE)
        }

        @Test
        @DisplayName("보유에 없는데 판 것이 더 많은 종목은 원장 시작 전 이력이라 건너뜀(매입가를 모름)")
        fun unknownCostHistoryIsSkipped() {
            enqueue(
                sell(
                    "S-OLD",
                    qty = 10,
                    amount = "750000",
                    at = clock.instant().minusSeconds(86_400),
                )
            )

            service.processFills()

            assertThat(fills.stateOf("S-OLD")).isEqualTo(FillState.SKIPPED)
            assertThat(lots.lots).isEmpty()
        }

        @Test
        @DisplayName("보유를 받지 못하면 원장을 시작하지 않고 체결도 그대로 둠")
        fun holdingsFailureKeepsQueue() {
            trading.accountFailure =
                banghak.stock.core.domain.error.BrokerUnavailableException("연결 실패")
            enqueue(buy("B-1", qty = 10, amount = "700000", at = clock.instant()))

            service.processFills()

            assertThat(ledger.started).isEmpty()
            assertThat(fills.stateOf("B-1")).isEqualTo(FillState.PENDING)
        }
    }

    @Nested
    @DisplayName("체결 반영")
    inner class Apply {
        @BeforeEach
        fun startLedger() {
            ledger.start(user, clock.instant())
        }

        @Test
        @DisplayName("매수 체결은 주문 출처를 이어받은 lot 이 되고 단가는 체결 금액 ÷ 수량임")
        fun buyOpensLot() {
            enqueue(
                buy("B-1", qty = 4, amount = "282000", at = later(1), origin = OrderOrigin.AUTO_BUY)
            )

            service.processFills()

            val lot = lots.lots.single()
            assertThat(lot.origin).isEqualTo(BuyOrigin.AUTO_BUY)
            assertThat(lot.unitCost).isEqualTo(TradingFixtures.krw("70500"))
            assertThat(lots.lotOrders[lot.id]).isEqualTo("B-1")
            assertThat(fills.stateOf("B-1")).isEqualTo(FillState.DONE)
        }

        @Test
        @DisplayName("매도 체결은 먼저 산 lot 부터 소진하고 소진 기록과 실현손익을 남김")
        fun sellDisposesFifo() {
            enqueue(buy("B-1", qty = 10, amount = "700000", at = later(1)))
            enqueue(buy("B-2", qty = 10, amount = "720000", at = later(2)))
            enqueue(sell("S-1", qty = 15, amount = "1125000", at = later(3), fee = "150"))

            service.processFills()

            assertThat(lots.lots.map { it.remainingQuantity })
                .containsExactly(Quantity.ZERO, Quantity.of(5))
            assertThat(lots.disposals.map { it.quantity })
                .containsExactly(Quantity.of(10), Quantity.of(5))
            assertThat(lots.disposals.first().realized).isEqualTo(TradingFixtures.krw("49900"))
            assertThat(recorded().filter { it == LotReduced::class.java }).hasSize(2)
            assertThat(recorded().filter { it == LotClosed::class.java }).hasSize(1)
        }

        @Test
        @DisplayName("lot 이 모자라면 매도를 막아 두고 그 종목의 뒤 체결도 기다리게 하며, 다른 종목은 계속 반영함")
        fun shortageBlocksOnlyThatSymbol() {
            // 뒤 매수(3주)로도 매도(5주)를 덮지 못해 재대조가 풀지 못하는 경우
            enqueue(sell("S-1", qty = 5, amount = "350000", at = later(1)))
            enqueue(buy("B-1", qty = 3, amount = "210000", at = later(2)))
            enqueue(
                buy(
                    "N-1",
                    qty = 1,
                    amount = "100",
                    at = later(3),
                    symbol = Symbol(samsung.market, "000660"),
                )
            )

            service.processFills()

            assertThat(fills.stateOf("S-1")).isEqualTo(FillState.BLOCKED)
            assertThat(fills.stateOf("B-1")).isEqualTo(FillState.PENDING)
            assertThat(fills.stateOf("N-1")).isEqualTo(FillState.DONE)
        }

        @Test
        @DisplayName("해외 체결은 체결 시각의 환율로 lot 을 만들고, 환율을 받지 못하면 미뤘다가 다음에 반영함")
        fun usFillUsesRateAtExecution() {
            val executedAt = later(1)
            enqueue(
                buy(
                    "U-1",
                    qty = 2,
                    amount = "200.00",
                    at = executedAt,
                    symbol = TradingFixtures.nvidia,
                )
            )

            service.processFills()
            assertThat(fills.stateOf("U-1")).isEqualTo(FillState.PENDING)

            marketData.rates[Currency.USD to Currency.KRW] =
                ExchangeRate(Currency.USD, Currency.KRW, BigDecimal("1400"), clock.instant())
            service.processFills()

            val lot = lots.lots.single()
            assertThat(lot.fxAtBuy?.asOf).isEqualTo(executedAt)
            assertThat(marketData.rateRequests).contains(executedAt)
        }
    }

    @Nested
    @DisplayName("lot 부족 재대조")
    inner class Reconcile {
        @BeforeEach
        fun startLedger() {
            ledger.start(user, clock.instant())
        }

        @Test
        @DisplayName("빠진 매수는 1분 이상 떨어진 두 번의 대조에서 같은 상태를 볼 때만 채우고, 다음 반영에서 매도를 소진함")
        fun fillsGapAfterTwoMatchingObservations() {
            enqueue(sell("S-1", qty = 10, amount = "750000", at = later(1)))
            trading.holdings += holding(samsung, qty = 3, avg = "65000")

            service.processFills()
            assertThat(lots.lots).describedAs("첫 대조에서는 채우지 않음").isEmpty()
            clock.advance(Duration.ofMinutes(1))
            service.processFills()

            val gap = lots.lots.single()
            assertThat(gap.remainingQuantity).isEqualTo(Quantity.of(13))
            assertThat(gap.isOpening).isTrue()
            assertThat(gap.boughtAt).isBefore(later(1))
            service.processFills()
            assertThat(fills.stateOf("S-1")).isEqualTo(FillState.DONE)
            assertThat(lots.lots.single().remainingQuantity).isEqualTo(Quantity.of(3))
        }

        @Test
        @DisplayName("두 대조 사이에 늦게 온 매수 이벤트가 대기열을 바꾸면 기초 lot 을 만들지 않고 그 매수로 풂")
        fun delayedEventPreventsPhantomLot() {
            enqueue(sell("S-1", qty = 10, amount = "750000", at = later(1)))
            trading.holdings += holding(samsung, qty = 3, avg = "65000")
            service.processFills()

            enqueue(buy("B-LATE", qty = 13, amount = "910000", at = later(5)))
            clock.advance(Duration.ofMinutes(1))
            service.processFills()
            service.processFills()

            assertThat(lots.lots.none { it.isOpening }).isTrue()
            assertThat(fills.stateOf("S-1")).isEqualTo(FillState.DONE)
        }

        @Test
        @DisplayName("보유 조회 중에 대기열이 바뀌면 그 대조는 판정하지 않음")
        fun queueChangeDuringHoldingsReadSkipsRound() {
            enqueue(sell("S-1", qty = 10, amount = "750000", at = later(1)))
            trading.holdings += holding(samsung, qty = 3, avg = "65000")
            service.processFills()
            trading.onHoldingsRead = {
                enqueue(
                    buy(
                        "N-1",
                        qty = 1,
                        amount = "100",
                        at = later(2),
                        symbol = Symbol(samsung.market, "000660"),
                    )
                )
            }
            clock.advance(Duration.ofMinutes(1))

            service.processFills()

            assertThat(lots.lots.none { it.isOpening }).isTrue()
        }

        @Test
        @DisplayName("보정 저장이 실패해도 5분이 아니라 다음 대조(1분 뒤)에서 다시 시도함")
        fun failedCorrectionRetriesNextRound() {
            enqueue(sell("S-1", qty = 10, amount = "750000", at = later(1)))
            trading.holdings += holding(samsung, qty = 3, avg = "65000")
            service.processFills()
            lots.saveFailures += IllegalStateException("DB 잠김")
            clock.advance(Duration.ofMinutes(1))
            service.processFills()
            assertThat(lots.lots).isEmpty()

            clock.advance(Duration.ofMinutes(1))
            service.processFills()

            assertThat(lots.lots.single().remainingQuantity).isEqualTo(Quantity.of(13))
        }

        @Test
        @DisplayName("보유와 기록이 맞으면 순서 문제라 매도 뒤의 매수를 바로 먼저 반영한 뒤 매도를 소진함")
        fun appliesLaterBuyFirst() {
            enqueue(sell("S-1", qty = 5, amount = "375000", at = later(1)))
            enqueue(buy("B-1", qty = 5, amount = "350000", at = later(3)))

            service.processFills()
            assertThat(fills.stateOf("B-1")).isEqualTo(FillState.DONE)
            service.processFills()

            assertThat(fills.stateOf("S-1")).isEqualTo(FillState.DONE)
            assertThat(lots.disposals.single().quantity).isEqualTo(Quantity.of(5))
        }

        @Test
        @DisplayName("매입가를 알 수 없으면 사람 확인으로 남기고, 보유 조회는 사용자마다 1분에 한 번만 함")
        fun needsReviewAndThrottles() {
            enqueue(sell("S-1", qty = 10, amount = "750000", at = later(1)))

            service.processFills()
            val readsAfterFirst = trading.holdingsReads
            clock.advance(Duration.ofSeconds(59))
            service.processFills()

            val item = fills.items.single()
            assertThat(item.state).isEqualTo(FillState.BLOCKED)
            assertThat(item.reason).startsWith("사람 확인 필요")
            assertThat(trading.holdingsReads).isEqualTo(readsAfterFirst)
            clock.advance(Duration.ofSeconds(1))
            service.processFills()
            assertThat(trading.holdingsReads).isEqualTo(readsAfterFirst + 1)
        }
    }

    private fun later(seconds: Long): Instant = clock.instant().plus(Duration.ofSeconds(seconds))

    private fun buy(
        orderId: String,
        qty: Long,
        amount: String,
        at: Instant,
        origin: OrderOrigin = OrderOrigin.MANUAL,
        symbol: Symbol = samsung,
    ) = fill(orderId, OrderSide.BUY, qty, amount, "0", at, origin, symbol)

    private fun sell(orderId: String, qty: Long, amount: String, at: Instant, fee: String = "0") =
        fill(orderId, OrderSide.SELL, qty, amount, fee, at, OrderOrigin.MANUAL, samsung)

    private fun fill(
        orderId: String,
        side: OrderSide,
        qty: Long,
        amount: String,
        fee: String,
        at: Instant,
        origin: OrderOrigin,
        symbol: Symbol,
    ): FillIncrement {
        val currency = symbol.market.currency
        return FillIncrement(
            user,
            orderId,
            symbol,
            side,
            Quantity.of(qty),
            Money.of(amount, currency),
            Money.of(fee, currency),
            Money.zero(currency),
            origin,
            at,
        )
    }

    private fun enqueue(fill: FillIncrement) = fills.enqueue(fill, clock.instant())

    private fun holding(symbol: Symbol, qty: Long, avg: String): BrokerHolding {
        val price = TradingFixtures.krw(avg)
        return BrokerHolding(
            symbol,
            Quantity.of(qty),
            price,
            price,
            price.times(Quantity.of(qty)),
            price.times(Quantity.of(qty)),
            Money.zero(Currency.KRW),
        )
    }

    private fun recorded(): List<Class<*>> =
        events.replay(user, null, 0).map { it.payload::class.java }.toList()
}
