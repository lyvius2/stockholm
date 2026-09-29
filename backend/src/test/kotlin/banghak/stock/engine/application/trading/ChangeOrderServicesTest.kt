package banghak.stock.engine.application.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.OrderRejectedException
import banghak.stock.core.domain.error.OrderResultUnknownException
import banghak.stock.core.domain.eventlog.OrderAmendRequested
import banghak.stock.core.domain.eventlog.OrderCancelRequested
import banghak.stock.core.domain.eventlog.OrderRejected
import banghak.stock.core.domain.eventlog.OrderResultUnknown
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.ManualAmendTrigger
import banghak.stock.core.domain.trading.OrderAmendment
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderStatus
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.SubmissionState
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.usecase.AmendOrderRequest
import banghak.stock.core.usecase.CancelOrderRequest
import banghak.stock.core.usecase.CancelPlacement
import banghak.stock.core.usecase.OrderPlacement
import banghak.stock.shared.crypto.UlidGenerator
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.FakeTradingPort
import banghak.stock.support.fakes.MemoryBrokerOrderStore
import banghak.stock.support.fakes.MemoryEventStore
import banghak.stock.support.fakes.MemoryFillQueue
import banghak.stock.support.fakes.MemorySubmissionStore
import banghak.stock.support.fakes.MemoryUserAccountPort
import banghak.stock.support.fakes.ScriptedGuardrail
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ChangeOrderServicesTest {
    private val clock = MutableClock(TradingFixtures.now)
    private val user = TradingFixtures.user
    private val guardrail = ScriptedGuardrail()
    private val trading = FakeTradingPort()
    private val events = MemoryEventStore()
    private val submissions = MemorySubmissionStore()
    private val orders = MemoryBrokerOrderStore()
    private val users = MemoryUserAccountPort()
    private val fills = MemoryFillQueue()
    private val journal = OrderJournal(events, submissions, orders, fills, clock)
    private val amendService =
        AmendOrderService(
            guardrail,
            trading,
            orders,
            SubmissionDispatcher(journal, submissions, clock),
            clock,
        )
    private val cancelService = CancelOrderService(trading, journal, UlidGenerator(clock))
    private val resolver = PendingSubmissionResolver(trading, journal, submissions, users, clock)
    private val key = ClientOrderId("01K6AMD0000000000000000001")
    private val otherKey = ClientOrderId("01K6AMD0000000000000000002")

    // 체결이 없는 100주 자동 매수 주문
    private val autoIntent =
        TradingFixtures.limitBuy(
            quantity = Quantity.of(100),
            trigger = TradingFixtures.autoBuy,
            origin = OrderOrigin.AUTO_BUY,
        )
    private val original = TradingFixtures.brokerRecord(intent = autoIntent, brokerOrderId = "B-1")

    @BeforeEach
    fun setUp() {
        users.save(TradingFixtures.account())
        orders.recordAccepted(
            TradingFixtures.brokerOrder(intent = autoIntent, brokerOrderId = "B-1"),
            false,
        )
        trading.details["B-1"] = original
    }

    @Nested
    @DisplayName("정정")
    inner class Amend {
        @Test
        @DisplayName("국내 가격 정정은 잔량을 함께 보내고, 새 주문을 원주문과 잇고 출처를 이어받음")
        fun amendsPriceWithRemainingQuantity() {
            val placement = amend(OrderAmendment(TradingFixtures.krw("71000"), null))

            assertThat(placement).isEqualTo(OrderPlacement.Accepted(key, "A-1"))
            val sent = trading.amendments.single()
            assertThat(sent.brokerOrderId).isEqualTo("B-1")
            assertThat(sent.quantity).isEqualTo(Quantity.of(100))
            val newOrder = orders.orders.getValue("A-1")
            assertThat(newOrder.replacesBrokerOrderId).isEqualTo("B-1")
            assertThat(newOrder.intent.origin).isEqualTo(OrderOrigin.AUTO_BUY)
            assertThat(guardrail.evaluated.single().first.trigger)
                .isEqualTo(ManualAmendTrigger(TradingFixtures.device, "B-1"))
            assertThat(recorded()).contains(OrderAmendRequested::class.java)
        }

        @Test
        @DisplayName("일부 체결된 국내 주문의 가격 정정은 잔량(100주 중 24주 체결이면 76주)을 보냄")
        fun partiallyFilledSendsRemaining() {
            trading.details["B-1"] =
                original.copy(
                    status = OrderStatus.PARTIALLY_FILLED,
                    filledQuantity = Quantity.of(24),
                )

            amend(OrderAmendment(TradingFixtures.krw("71000"), null))

            assertThat(trading.amendments.single().quantity).isEqualTo(Quantity.of(76))
        }

        @Test
        @DisplayName("미국 정정은 가격만 보내고, 미국 수량 정정은 받지 않음")
        fun usAmendsPriceOnly() {
            trading.details["U-1"] =
                TradingFixtures.brokerRecord(
                    intent =
                        TradingFixtures.limitBuy(
                            symbol = TradingFixtures.nvidia,
                            price = TradingFixtures.usd("100"),
                        ),
                    brokerOrderId = "U-1",
                )

            amend(OrderAmendment(TradingFixtures.usd("99"), null), brokerOrderId = "U-1")

            assertThat(trading.amendments.single().quantity).isNull()
            assertThatThrownBy {
                    amend(
                        OrderAmendment(null, Quantity.of(1)),
                        brokerOrderId = "U-1",
                        request = otherKey,
                    )
                }
                .isInstanceOf(InvalidValueException::class.java)
        }

        @Test
        @DisplayName("잔량을 넘는 수량 정정은 증권사에 묻기 전에 거부함")
        fun rejectsQuantityOverRemaining() {
            assertThatThrownBy { amend(OrderAmendment(null, Quantity.of(101))) }
                .isInstanceOf(InvalidValueException::class.java)
            assertThat(trading.amendments).isEmpty()
            assertThat(guardrail.evaluated).isEmpty()
        }

        @Test
        @DisplayName("같은 키의 같은 정정은 첫 결과를 돌려주고, 같은 키에 다른 정정 내용은 거부함")
        fun sameKeyNeedsSameContent() {
            val first = amend(OrderAmendment(TradingFixtures.krw("71000"), null))

            assertThat(amend(OrderAmendment(TradingFixtures.krw("71000"), null))).isEqualTo(first)
            assertThatThrownBy { amend(OrderAmendment(TradingFixtures.krw("72000"), null)) }
                .isInstanceOf(InvalidValueException::class.java)
            assertThat(trading.amendments).hasSize(1)
        }

        @Nested
        @DisplayName("결과 모름 확인(원주문 상태로 판정)")
        inner class Resolution {
            @BeforeEach
            fun sendUnknown() {
                trading.amendResults += OrderResultUnknownException("타임아웃")
                assertThat(amend(OrderAmendment(TradingFixtures.krw("71000"), null)))
                    .isEqualTo(OrderPlacement.Pending(key))
            }

            @Test
            @DisplayName("30초 안에는 판정하지 않고, 그 뒤 원주문이 그대로 열려 있으면 정정이 반영되지 않은 것으로 봄")
            fun untouchedOriginalMeansNotApplied() {
                clock.advance(Duration.ofSeconds(29))
                resolver.resolvePending()
                assertThat(submissions.records.getValue(key).state)
                    .isEqualTo(SubmissionState.UNKNOWN)

                clock.advance(Duration.ofSeconds(1))
                resolver.resolvePending()

                assertThat(submissions.records.getValue(key).state)
                    .isEqualTo(SubmissionState.REJECTED)
            }

            @Test
            @DisplayName("원주문이 정정됐어도 새 주문 번호는 속성으로 고르지 않고 사람 확인으로 둠")
            fun replacedOriginalNeedsReview() {
                trading.details["B-1"] = original.copy(status = OrderStatus.REPLACED)
                trading.openOrders +=
                    TradingFixtures.brokerRecord(
                            intent = autoIntent.copy(limitPrice = TradingFixtures.krw("71000")),
                            brokerOrderId = "LOOKS-LIKE-IT",
                        )
                        .copy(orderedAt = clock.instant().plusSeconds(1))
                clock.advance(Duration.ofSeconds(30))

                resolver.resolvePending()

                val record = submissions.records.getValue(key)
                assertThat(record.state).isEqualTo(SubmissionState.NEEDS_REVIEW)
                assertThat(record.brokerOrderId).isNull()
            }

            @Test
            @DisplayName("원주문이 정정 처리 중이면 기다림")
            fun pendingAmendWaits() {
                trading.details["B-1"] = original.copy(status = OrderStatus.PENDING_AMEND)
                clock.advance(Duration.ofSeconds(30))

                resolver.resolvePending()

                assertThat(submissions.records.getValue(key).state)
                    .isEqualTo(SubmissionState.UNKNOWN)
            }
        }
    }

    @Nested
    @DisplayName("취소")
    inner class Cancel {
        @Test
        @DisplayName("열린 주문을 취소하고 요청을 이벤트로 남김")
        fun cancelsOpenOrder() {
            val placement = cancel()

            assertThat(placement).isEqualTo(CancelPlacement.Requested("C-1"))
            assertThat(trading.cancels).containsExactly("B-1")
            assertThat(recorded()).containsExactly(OrderCancelRequested::class.java)
        }

        @Test
        @DisplayName("처리 중이거나 닫힌 주문은 증권사에 보내지 않고 거부함")
        fun rejectsUnchangeableOrder() {
            trading.details["B-1"] = original.copy(status = OrderStatus.PENDING_CANCEL)

            assertThatThrownBy { cancel() }.isInstanceOf(InvalidValueException::class.java)
            assertThat(trading.cancels).isEmpty()
        }

        @Test
        @DisplayName("결과를 모르면 확인 중으로 두고, 거부되면 거부를 남긴 뒤 알림")
        fun recordsUnknownAndRejected() {
            trading.cancelResults += OrderResultUnknownException("타임아웃")
            trading.cancelResults += OrderRejectedException("토스가 주문을 받지 않음(already-filled)")

            assertThat(cancel()).isEqualTo(CancelPlacement.Pending)
            assertThatThrownBy { cancel() }.isInstanceOf(OrderRejectedException::class.java)

            assertThat(recorded())
                .containsExactly(
                    OrderCancelRequested::class.java,
                    OrderResultUnknown::class.java,
                    OrderCancelRequested::class.java,
                    OrderRejected::class.java,
                )
        }

        private fun cancel() =
            cancelService.cancel(CancelOrderRequest(user, TradingFixtures.device, "B-1"))
    }

    private fun amend(
        amendment: OrderAmendment,
        brokerOrderId: String = "B-1",
        request: ClientOrderId = key,
    ): OrderPlacement =
        amendService.amend(
            AmendOrderRequest(
                request,
                user,
                TradingFixtures.device,
                brokerOrderId,
                amendment,
                emptySet(),
            )
        )

    private fun recorded(): List<Class<*>> =
        events.replay(user, null, 0).map { it.payload::class.java }.toList()
}
