package banghak.stock.engine.application.trading

import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.error.ConfirmationRequiredException
import banghak.stock.core.domain.error.GuardrailViolationException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.OrderRejectedException
import banghak.stock.core.domain.error.OrderResultUnknownException
import banghak.stock.core.domain.eventlog.ConditionalOrderCancelRequested
import banghak.stock.core.domain.eventlog.ConditionalOrderRegistered
import banghak.stock.core.domain.eventlog.ConditionalOrderRequested
import banghak.stock.core.domain.eventlog.GuardrailEvaluated
import banghak.stock.core.domain.eventlog.OrderRejected
import banghak.stock.core.domain.eventlog.OrderResultUnknown
import banghak.stock.core.domain.guardrail.GuardrailFinding
import banghak.stock.core.domain.guardrail.GuardrailVerdict
import banghak.stock.core.domain.guardrail.HighValueOrder
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.ConditionalFixtures
import banghak.stock.core.domain.trading.ConditionalOrderIntent
import banghak.stock.core.domain.trading.ConditionalOrderStatus
import banghak.stock.core.domain.trading.ConditionalOrderType
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.SubmissionState
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.usecase.AmendConditionalOrderRequest
import banghak.stock.core.usecase.CancelConditionalOrderRequest
import banghak.stock.core.usecase.ConditionalCancelPlacement
import banghak.stock.core.usecase.ConditionalPlacement
import banghak.stock.core.usecase.RegisterConditionalOrderRequest
import banghak.stock.shared.crypto.UlidGenerator
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.FakeConditionalOrderPort
import banghak.stock.support.fakes.MemoryConditionalSubmissionStore
import banghak.stock.support.fakes.MemoryEventStore
import banghak.stock.support.fakes.MemoryUserAccountPort
import banghak.stock.support.fakes.ScriptedGuardrail
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 조건주문 등록·수정·취소와 결과 모름 확인.
 * 실제 주문 API 대신 가짜 포트를 씀.
 */
class ConditionalOrderServicesTest {
    private val clock = MutableClock(TradingFixtures.now)
    private val user = TradingFixtures.user
    private val guardrail = ScriptedGuardrail()
    private val port = FakeConditionalOrderPort()
    private val events = MemoryEventStore()
    private val submissions = MemoryConditionalSubmissionStore()
    private val users = MemoryUserAccountPort()
    private val journal = ConditionalOrderJournal(events, submissions, clock)
    private val service = ConditionalOrderService(guardrail, port, journal, submissions, clock)
    private val cancelService = CancelConditionalOrderService(port, journal, UlidGenerator(clock))
    private val resolver = PendingConditionalOrderResolver(port, journal, submissions, users, clock)
    private val key = ClientOrderId("01K6CND0000000000000000001")
    private val oco = ConditionalFixtures.oco()

    @BeforeEach
    fun setUp() {
        users.save(TradingFixtures.account())
    }

    @Nested
    @DisplayName("등록")
    inner class Register {
        @Test
        @DisplayName("가드레일을 거쳐 등록하고 요청·판정·등록을 이벤트로 남김")
        fun registersThroughGuardrail() {
            val placement = register(oco)

            assertThat(placement).isEqualTo(ConditionalPlacement.Registered(key, "CO-1"))
            assertThat(guardrail.evaluatedConditional).containsExactly(oco)
            assertThat(port.registrations.single().clientOrderId).isEqualTo(key)
            assertThat(submissions.stateOf(key)).isEqualTo(SubmissionState.ACCEPTED)
            assertThat(recorded())
                .containsExactly(
                    GuardrailEvaluated::class.java,
                    ConditionalOrderRequested::class.java,
                    ConditionalOrderRegistered::class.java,
                )
        }

        @Test
        @DisplayName("가드레일이 거부하면 보내지 않음")
        fun guardrailRejectionSendsNothing() {
            guardrail.verdict =
                GuardrailVerdict.Rejected(
                    listOf(GuardrailFinding.Violation("ConditionalExpiry", "만료일이 지남")),
                    emptyList(),
                )

            assertThatThrownBy { register(oco) }
                .isInstanceOf(GuardrailViolationException::class.java)
            assertThat(port.registrations).isEmpty()
            assertThat(submissions.stateOf(key)).isNull()
        }

        @Test
        @DisplayName("고액 노트는 사람이 확인해야 보내고, 확인하면 토스 고액 확인을 실음")
        fun highValueNeedsConfirmation() {
            guardrail.verdict =
                GuardrailVerdict.Passed(listOf(GuardrailFinding.Note(HighValueOrder.NAME, "고액")))

            assertThatThrownBy { register(oco) }
                .isInstanceOf(ConfirmationRequiredException::class.java)
            register(oco, confirmed = setOf(HighValueOrder.NAME))

            assertThat(port.registrations.single().isHighValueConfirmed).isTrue()
        }

        @Test
        @DisplayName("결과를 모르면 확인 중으로 두고, 같은 키로 다시 와도 다시 보내지 않음")
        fun unknownIsPendingAndNeverResent() {
            port.registerResults += OrderResultUnknownException("응답 없음")

            assertThat(register(oco)).isEqualTo(ConditionalPlacement.Pending(key))
            assertThat(register(oco)).isEqualTo(ConditionalPlacement.Pending(key))
            assertThat(port.registrations).hasSize(1)
            assertThat(guardrail.evaluatedConditional).hasSize(1)
            assertThat(submissions.stateOf(key)).isEqualTo(SubmissionState.UNKNOWN)
        }

        @Test
        @DisplayName("같은 키에 다른 내용이 오면 거부함")
        fun sameKeyDifferentContentIsRejected() {
            register(oco)

            assertThatThrownBy { register(ConditionalFixtures.oco(takeProfit = krw("90000"))) }
                .isInstanceOf(InvalidValueException::class.java)
        }

        @Test
        @DisplayName("토스가 거부하면 REJECTED, 닿기 전 실패면 NOT_SENT 로 남기고 예외를 올림")
        fun recordsRejectionAndNotSent() {
            port.registerResults += OrderRejectedException("duplicate-conditional-order")
            assertThatThrownBy { register(oco) }.isInstanceOf(OrderRejectedException::class.java)
            assertThat(submissions.stateOf(key)).isEqualTo(SubmissionState.REJECTED)

            val other = ClientOrderId("01K6CND0000000000000000002")
            port.registerResults += BrokerUnavailableException("서킷 열림")
            assertThatThrownBy { register(oco, other) }
                .isInstanceOf(BrokerUnavailableException::class.java)
            assertThat(submissions.stateOf(other)).isEqualTo(SubmissionState.NOT_SENT)
        }
    }

    @Nested
    @DisplayName("수정")
    inner class Amend {
        @BeforeEach
        fun existing() {
            port.details["CO-1"] = ConditionalFixtures.record(oco, "CO-1")
        }

        @Test
        @DisplayName("열린 조건주문을 가드레일을 거쳐 다시 설정하고 새 번호를 돌려줌")
        fun amendsOpenConditionalOrder() {
            val changed = ConditionalFixtures.oco(takeProfit = krw("85000"))

            val placement = amend(changed)

            assertThat(placement).isEqualTo(ConditionalPlacement.Registered(key, "CM-1"))
            assertThat(port.amendments.single().first).isEqualTo("CO-1")
            assertThat(guardrail.evaluatedConditional).containsExactly(changed)
        }

        @Test
        @DisplayName("타입 전환(OCO → SINGLE)도 보냄")
        fun switchesType() {
            val single = ConditionalFixtures.single(side = OrderSide.SELL, trigger = krw("60000"))

            amend(single)

            assertThat(port.amendments.single().second.intent.type)
                .isEqualTo(ConditionalOrderType.SINGLE)
        }

        @Test
        @DisplayName("이미 끝난 조건주문이나 다른 종목으로의 수정은 보내지 않음")
        fun rejectsClosedOrOtherSymbol() {
            port.details["CO-1"] =
                ConditionalFixtures.record(oco, "CO-1", ConditionalOrderStatus.COMPLETED)
            assertThatThrownBy { amend(oco) }.isInstanceOf(InvalidValueException::class.java)

            port.details["CO-1"] = ConditionalFixtures.record(oco, "CO-1")
            val nvidia =
                ConditionalFixtures.single(
                    trigger = TradingFixtures.usd("100"),
                    symbol = TradingFixtures.nvidia,
                )
            assertThatThrownBy { amend(nvidia) }.isInstanceOf(InvalidValueException::class.java)
            assertThat(port.amendments).isEmpty()
        }
    }

    @Nested
    @DisplayName("취소")
    inner class Cancel {
        @Test
        @DisplayName("취소 요청을 남기고 보냄. 결과를 모르면 확인 중으로 돌려줌")
        fun cancelsAndReportsUnknown() {
            assertThat(cancel()).isEqualTo(ConditionalCancelPlacement.Canceled)

            port.cancelResults += OrderResultUnknownException("응답 없음")
            assertThat(cancel()).isEqualTo(ConditionalCancelPlacement.Pending)
            assertThat(recorded())
                .containsExactly(
                    ConditionalOrderCancelRequested::class.java,
                    ConditionalOrderCancelRequested::class.java,
                    OrderResultUnknown::class.java,
                )
        }

        @Test
        @DisplayName("토스가 거부하면 거부를 남기고 예외를 올림")
        fun recordsRejection() {
            port.cancelResults += OrderRejectedException("conditional-order-not-found")

            assertThatThrownBy { cancel() }.isInstanceOf(OrderRejectedException::class.java)
            assertThat(recorded())
                .containsExactly(
                    ConditionalOrderCancelRequested::class.java,
                    OrderRejected::class.java,
                )
        }

        private fun cancel() =
            cancelService.cancel(
                CancelConditionalOrderRequest(user, TradingFixtures.device, "CO-1")
            )
    }

    @Nested
    @DisplayName("결과 모름 확인")
    inner class Resolve {
        @Test
        @DisplayName("보내는 중 30초 유예 뒤 목록에서 같은 모양 한 건을 찾으면 등록으로 확정함")
        fun findsRegistrationByAttributes() {
            port.registerResults += OrderResultUnknownException("응답 없음")
            register(oco)
            port.open += ConditionalFixtures.record(oco, "CO-7", createdAt = clock.instant())

            clock.advance(Duration.ofSeconds(31))
            resolver.resolvePending()

            assertThat(submissions.find(user, key)?.conditionalOrderId).isEqualTo("CO-7")
            assertThat(submissions.stateOf(key)).isEqualTo(SubmissionState.ACCEPTED)
            assertThat(port.registrations).hasSize(1)
        }

        @Test
        @DisplayName("결과 모름 등록도 정확히 30초 전에는 목록을 읽지 않고, 30초가 되면 확인함")
        fun unknownRegistrationWaitsThirtySeconds() {
            port.registerResults += OrderResultUnknownException("응답 없음")
            register(oco)
            port.open += ConditionalFixtures.record(oco, "CO-7", createdAt = clock.instant())

            clock.advance(Duration.ofSeconds(30).minusMillis(1))
            resolver.resolvePending()
            assertThat(port.queries).isEmpty()
            assertThat(submissions.stateOf(key)).isEqualTo(SubmissionState.UNKNOWN)

            clock.advance(Duration.ofMillis(1))
            resolver.resolvePending()
            assertThat(submissions.stateOf(key)).isEqualTo(SubmissionState.ACCEPTED)
        }

        @Test
        @DisplayName("SINGLE 은 같은 모양이 한 건이어도 방향을 확인할 수 없어 확정하지 않고 사람이 확인함")
        fun singleIsNeverConfirmedByAttributes() {
            val single = ConditionalFixtures.single()
            port.registerResults += OrderResultUnknownException("응답 없음")
            register(single)
            port.open += ConditionalFixtures.record(single, "CO-7", createdAt = clock.instant())

            clock.advance(Duration.ofSeconds(30))
            resolver.resolvePending()

            assertThat(submissions.stateOf(key)).isEqualTo(SubmissionState.NEEDS_REVIEW)
            assertThat(submissions.find(user, key)?.conditionalOrderId).isNull()
        }

        @Test
        @DisplayName("목록이 상한에서 잘리면 판정하지 않고, 5분 안에 못 찾으면 사람이 확인함")
        fun truncatedListWaitsThenNeedsReview() {
            port.registerResults += OrderResultUnknownException("응답 없음")
            register(oco)
            port.open += ConditionalFixtures.record(oco, "CO-7", createdAt = clock.instant())
            port.hasMorePages = true

            clock.advance(Duration.ofSeconds(31))
            resolver.resolvePending()
            assertThat(submissions.stateOf(key)).isEqualTo(SubmissionState.UNKNOWN)

            clock.advance(Duration.ofMinutes(5))
            resolver.resolvePending()
            assertThat(submissions.stateOf(key)).isEqualTo(SubmissionState.NEEDS_REVIEW)
        }

        @Test
        @DisplayName("수정 결과를 모를 때 기존 조건주문이 그대로 열려 있으면 반영 안 됨, 사라졌으면 사람이 확인함")
        fun amendmentJudgedByOriginal() {
            port.details["CO-1"] = ConditionalFixtures.record(oco, "CO-1")
            port.amendResults += OrderResultUnknownException("응답 없음")
            amend(ConditionalFixtures.oco(takeProfit = krw("85000")))

            clock.advance(Duration.ofSeconds(31))
            resolver.resolvePending()
            assertThat(submissions.stateOf(key)).isEqualTo(SubmissionState.REJECTED)

            val second = ClientOrderId("01K6CND0000000000000000003")
            port.amendResults += OrderResultUnknownException("응답 없음")
            amend(ConditionalFixtures.oco(takeProfit = krw("86000")), second)
            port.details.remove("CO-1")

            clock.advance(Duration.ofSeconds(31))
            resolver.resolvePending()
            assertThat(submissions.stateOf(second)).isEqualTo(SubmissionState.NEEDS_REVIEW)
        }
    }

    private fun register(
        intent: ConditionalOrderIntent,
        request: ClientOrderId = key,
        confirmed: Set<String> = emptySet(),
    ): ConditionalPlacement =
        service.register(RegisterConditionalOrderRequest(request, intent, confirmed))

    private fun amend(intent: ConditionalOrderIntent, request: ClientOrderId = key) =
        service.amend(AmendConditionalOrderRequest(request, "CO-1", intent, emptySet()))

    private fun recorded(): List<Class<*>> =
        events.replay(user, null, 0).map { it.payload::class.java }.toList()
}
