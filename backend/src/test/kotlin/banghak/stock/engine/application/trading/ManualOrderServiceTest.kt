package banghak.stock.engine.application.trading

import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.error.ConfirmationRequiredException
import banghak.stock.core.domain.error.GuardrailViolationException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.OrderRejectedException
import banghak.stock.core.domain.error.OrderResultUnknownException
import banghak.stock.core.domain.eventlog.GuardrailEvaluated
import banghak.stock.core.domain.eventlog.OrderIntended
import banghak.stock.core.domain.eventlog.OrderRejected
import banghak.stock.core.domain.eventlog.OrderResultUnknown
import banghak.stock.core.domain.eventlog.OrderSubmitted
import banghak.stock.core.domain.guardrail.GuardrailFinding
import banghak.stock.core.domain.guardrail.GuardrailVerdict
import banghak.stock.core.domain.guardrail.HighValueOrder
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.OrderIntent
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderStatus
import banghak.stock.core.domain.trading.OrderSubmission
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.RecommendationTrigger
import banghak.stock.core.domain.trading.SubmissionRecord
import banghak.stock.core.domain.trading.SubmissionState
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.usecase.ManualOrderRequest
import banghak.stock.core.usecase.OrderPlacement
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.FakeTradingPort
import banghak.stock.support.fakes.MemoryBrokerOrderStore
import banghak.stock.support.fakes.MemoryEventStore
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

class ManualOrderServiceTest {
    private val clock = MutableClock(TradingFixtures.now)
    private val guardrail = ScriptedGuardrail()
    private val trading = FakeTradingPort()
    private val events = MemoryEventStore()
    private val submissions = MemorySubmissionStore()
    private val orders = MemoryBrokerOrderStore()
    private val users = MemoryUserAccountPort()
    private val journal = OrderJournal(events, submissions, orders, clock)
    private val service =
        ManualOrderService(guardrail, trading, SubmissionDispatcher(journal, submissions, clock))
    private val resolver = PendingSubmissionResolver(trading, journal, submissions, users, clock)
    private val intent = TradingFixtures.limitBuy()
    private val key = ClientOrderId("01K6REQ0000000000000000001")

    @BeforeEach
    fun registerUser() {
        users.save(TradingFixtures.account())
    }

    @Test
    @DisplayName("가드레일을 통과하면 화면이 준 멱등 키로 접수하고, 판정·의도·접수와 요청 상태를 기록함")
    fun acceptsAndRecords() {
        val placement = place()

        assertThat(placement).isEqualTo(OrderPlacement.Accepted(key, "B-1"))
        assertThat(trading.submissions.single().clientOrderId).isEqualTo(key)
        assertThat(submissions.records.getValue(key).state).isEqualTo(SubmissionState.ACCEPTED)
        assertThat(orders.orders.getValue("B-1").status).isEqualTo(OrderStatus.PENDING)
        assertThat(recorded())
            .containsExactly(
                GuardrailEvaluated::class.java,
                OrderIntended::class.java,
                OrderSubmitted::class.java,
            )
    }

    @Test
    @DisplayName("가드레일이 거부하면 요청 기록도 남기지 않고 증권사에 보내지 않음")
    fun rejectedVerdictSendsNothing() {
        guardrail.verdict =
            GuardrailVerdict.Rejected(
                listOf(GuardrailFinding.Violation("SessionOpen", "장이 열려 있지 않음")),
                emptyList(),
            )

        assertThatThrownBy { place() }
            .isInstanceOf(GuardrailViolationException::class.java)
            .hasMessageContaining("SessionOpen")
        assertThat(trading.submissions).isEmpty()
        assertThat(submissions.records).isEmpty()
        assertThat(recorded()).containsExactly(GuardrailEvaluated::class.java)
    }

    @Nested
    @DisplayName("확인 노트")
    inner class Confirmation {
        private val highValue =
            GuardrailVerdict.Passed(listOf(GuardrailFinding.Note(HighValueOrder.NAME, "1억원 이상")))

        @Test
        @DisplayName("사람이 확인하지 않은 노트가 있으면 보내지 않음")
        fun unconfirmedNoteBlocks() {
            guardrail.verdict = highValue

            assertThatThrownBy { place() }.isInstanceOf(ConfirmationRequiredException::class.java)
            assertThat(trading.submissions).isEmpty()
        }

        @Test
        @DisplayName("고액 노트를 확인하면 증권사에 고액 확인을 함께 보냄")
        fun confirmedHighValueIsSent() {
            guardrail.verdict = highValue

            place(confirmed = setOf(HighValueOrder.NAME))

            assertThat(trading.submissions.single().isHighValueConfirmed).isTrue()
            assertThat(orders.highValueConfirmed.values.single()).isTrue()
        }
    }

    @Nested
    @DisplayName("같은 멱등 키의 다시 보내기(중복 클릭·응답 유실)")
    inner class SameKey {
        @Test
        @DisplayName("이미 접수된 키는 가드레일·증권사를 거치지 않고 첫 결과를 돌려줌")
        fun returnsFirstResult() {
            val first = place()

            val second = place()

            assertThat(second).isEqualTo(first)
            assertThat(trading.submissions).hasSize(1)
            assertThat(guardrail.evaluated).hasSize(1)
        }

        @Test
        @DisplayName("같은 키에 다른 주문 내용이 오면 첫 결과를 돌려주지 않고 거부함")
        fun differentContentIsRejected() {
            place()

            assertThatThrownBy {
                    service.place(
                        ManualOrderRequest(key, intent.copy(quantity = Quantity.of(11)), emptySet())
                    )
                }
                .isInstanceOf(InvalidValueException::class.java)
            assertThat(trading.submissions).hasSize(1)
        }

        @Test
        @DisplayName("결과를 모르는 키는 확인 중을 돌려주고 다시 보내지 않음")
        fun unknownKeyStaysPending() {
            trading.placeResults += OrderResultUnknownException("타임아웃")
            place()

            assertThat(place()).isEqualTo(OrderPlacement.Pending(key))
            assertThat(trading.submissions).hasSize(1)
        }

        @Test
        @DisplayName("증권사가 받지 않은 키는 같은 거부를 다시 알리고 보내지 않음")
        fun rejectedKeyStaysRejected() {
            trading.placeResults +=
                OrderRejectedException("토스가 주문을 받지 않음(insufficient-buying-power)")
            assertThatThrownBy { place() }.isInstanceOf(OrderRejectedException::class.java)

            assertThatThrownBy { place() }
                .isInstanceOf(OrderRejectedException::class.java)
                .hasMessageContaining("insufficient-buying-power")
            assertThat(trading.submissions).hasSize(1)
        }
    }

    @Nested
    @DisplayName("결과 모름 확인(읽기 전용)")
    inner class Resolution {
        @Test
        @DisplayName("증권사 미체결에서 보낸 뒤 접수된 같은 주문을 찾으면 그 주문으로 기록하고, 다시 보내지 않음")
        fun matchesOpenOrderWithoutResending() {
            trading.placeResults += OrderResultUnknownException("타임아웃")
            assertThat(place()).isEqualTo(OrderPlacement.Pending(key))
            trading.openOrders += landed("B-77")

            resolver.resolvePending()

            assertThat(trading.submissions).hasSize(1)
            assertThat(submissions.records.getValue(key).brokerOrderId).isEqualTo("B-77")
            assertThat(place()).isEqualTo(OrderPlacement.Accepted(key, "B-77"))
            assertThat(recorded())
                .containsExactly(
                    GuardrailEvaluated::class.java,
                    OrderIntended::class.java,
                    OrderResultUnknown::class.java,
                    OrderSubmitted::class.java,
                )
        }

        @Test
        @DisplayName("종료 목록이 상한에서 잘리면 미체결에 맞는 주문이 있어도 이번에는 판정하지 않음")
        fun truncatedClosedPagesDeferDecision() {
            trading.placeResults += OrderResultUnknownException("타임아웃")
            place()
            trading.openOrders += landed("B-77")
            trading.hasMoreClosedPages = true

            resolver.resolvePending()

            assertThat(submissions.records.getValue(key).state).isEqualTo(SubmissionState.UNKNOWN)
        }

        @Test
        @DisplayName("이미 체결돼 종료 목록에 있는 주문도 찾음")
        fun matchesClosedOrder() {
            trading.placeResults += OrderResultUnknownException("타임아웃")
            place()
            trading.closedOrders += landed("B-88").copy(status = OrderStatus.FILLED)

            resolver.resolvePending()

            assertThat(submissions.records.getValue(key).brokerOrderId).isEqualTo("B-88")
        }

        @Test
        @DisplayName("찾지 못하면 계속 확인하다 5분이 되면 멈추고 사람 확인으로 둠: 4분 59초까지는 조회함")
        fun givesUpAfterFiveMinutes() {
            trading.placeResults += OrderResultUnknownException("타임아웃")
            place()

            clock.advance(Duration.ofSeconds(299))
            resolver.resolvePending()
            val readsBeforeLimit = trading.orderListReads
            clock.advance(Duration.ofSeconds(1))
            resolver.resolvePending()

            assertThat(readsBeforeLimit).isPositive()
            assertThat(trading.orderListReads).isEqualTo(readsBeforeLimit)
            assertThat(place()).isEqualTo(OrderPlacement.NeedsReview(key))
            assertThat(trading.submissions).hasSize(1)
        }

        @Test
        @DisplayName("맞는 주문이 여러 건이면 고르지 않고 사람 확인으로 둠")
        fun ambiguousNeedsReview() {
            trading.placeResults += OrderResultUnknownException("타임아웃")
            place()
            trading.openOrders += listOf(landed("B-1"), landed("B-2"))

            resolver.resolvePending()

            assertThat(submissions.records.getValue(key).state)
                .isEqualTo(SubmissionState.NEEDS_REVIEW)
        }

        @Test
        @DisplayName("한 주문은 한 요청에만 연결됨: 같은 내용의 두 요청이 모두 모를 때 주문이 하나면 하나만 연결함")
        fun oneOrderClaimsOneSubmission() {
            val secondKey = ClientOrderId("01K6REQ0000000000000000002")
            repeat(2) { trading.placeResults += OrderResultUnknownException("타임아웃") }
            place()
            service.place(ManualOrderRequest(secondKey, intent, emptySet()))
            trading.openOrders += landed("B-1")

            resolver.resolvePending()

            val linked = submissions.records.values.mapNotNull { it.brokerOrderId }
            assertThat(linked).containsExactly("B-1")
        }

        @Test
        @DisplayName("데몬이 보내는 중에 멈춘 요청은 30초가 지난 뒤부터 확인함(아직 날아가는 요청과 겹치지 않게)")
        fun sendingRecordWaitsForInFlightGrace() {
            submissions.tryBegin(
                SubmissionRecord(
                    OrderSubmission(intent, key, isHighValueConfirmed = false),
                    TradingFixtures.device,
                    SubmissionState.SENDING,
                    null,
                    null,
                    clock.instant(),
                )
            )
            trading.openOrders += landed("B-5")

            clock.advance(Duration.ofSeconds(29))
            resolver.resolvePending()
            assertThat(trading.orderListReads).isZero()
            clock.advance(Duration.ofSeconds(1))
            resolver.resolvePending()

            assertThat(submissions.records.getValue(key).brokerOrderId).isEqualTo("B-5")
        }
    }

    @Test
    @DisplayName("증권사가 받지 않으면 거부를, 닿기 전에 실패하면 보내지 못함을 기록하고 예외를 그대로 알림")
    fun recordsBrokerFailures() {
        trading.placeResults += OrderRejectedException("토스가 주문을 받지 않음(insufficient-buying-power)")
        trading.placeResults += BrokerUnavailableException("토스에 연결할 수 없음")
        val otherKey = ClientOrderId("01K6REQ0000000000000000003")

        assertThatThrownBy { place() }.isInstanceOf(OrderRejectedException::class.java)
        assertThatThrownBy { service.place(ManualOrderRequest(otherKey, intent, emptySet())) }
            .isInstanceOf(BrokerUnavailableException::class.java)

        assertThat(submissions.records.getValue(key).state).isEqualTo(SubmissionState.REJECTED)
        assertThat(submissions.records.getValue(otherKey).state).isEqualTo(SubmissionState.NOT_SENT)
        assertThat(recorded().filter { it == OrderRejected::class.java }).hasSize(2)
        assertThat(orders.orders).isEmpty()
    }

    @Test
    @DisplayName("사람이 낸 주문만 받음: AI 추천 승인은 추천 기능과 함께, 자동 주문은 받지 않음")
    fun acceptsOnlyManualTriggers() {
        val recommended =
            TradingFixtures.limitBuy(
                trigger = RecommendationTrigger("r-1"),
                origin = OrderOrigin.AI_RECOMMENDED,
            )
        val automatic =
            TradingFixtures.limitBuy(
                trigger = TradingFixtures.autoBuy,
                origin = OrderOrigin.AUTO_BUY,
            )

        assertThatThrownBy { service.place(ManualOrderRequest(key, recommended, emptySet())) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { service.place(ManualOrderRequest(key, automatic, emptySet())) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThat(guardrail.evaluated).isEmpty()
    }

    private fun place(confirmed: Set<String> = emptySet()): OrderPlacement =
        service.place(ManualOrderRequest(key, intent, confirmed))

    // 보낸 순간 이후에 토스에 접수된 같은 내용의 주문
    private fun landed(brokerOrderId: String, source: OrderIntent = intent) =
        TradingFixtures.brokerRecord(intent = source, brokerOrderId = brokerOrderId)
            .copy(orderedAt = clock.instant().plusSeconds(1))

    private fun recorded(): List<Class<*>> =
        events.replay(TradingFixtures.user, null, 0).map { it.payload::class.java }.toList()
}
