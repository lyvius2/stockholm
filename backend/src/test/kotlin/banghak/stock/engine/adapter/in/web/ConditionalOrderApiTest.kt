package banghak.stock.engine.adapter.`in`.web

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.ConditionLeg
import banghak.stock.core.domain.trading.ConditionLegRecord
import banghak.stock.core.domain.trading.ConditionLegStatus
import banghak.stock.core.domain.trading.ConditionalOrderRecord
import banghak.stock.core.domain.trading.ConditionalOrderScope
import banghak.stock.core.domain.trading.ConditionalOrderStatus
import banghak.stock.core.domain.trading.ConditionalOrderType
import banghak.stock.core.domain.trading.ConditionalOrdersPage
import banghak.stock.core.domain.trading.ConditionalOrdersQuery
import banghak.stock.core.domain.trading.OrderKind
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.domain.trading.TradingFixtures.usd
import banghak.stock.core.usecase.AmendConditionalOrderRequest
import banghak.stock.core.usecase.AmendConditionalOrderUseCase
import banghak.stock.core.usecase.CancelConditionalOrderRequest
import banghak.stock.core.usecase.CancelConditionalOrderUseCase
import banghak.stock.core.usecase.ConditionalCancelPlacement
import banghak.stock.core.usecase.ConditionalPlacement
import banghak.stock.core.usecase.ListConditionalOrdersUseCase
import banghak.stock.core.usecase.RegisterConditionalOrderRequest
import banghak.stock.core.usecase.RegisterConditionalOrderUseCase
import banghak.stock.engine.adapter.`in`.web.order.ConditionalOrderController
import banghak.stock.support.web.ApiTestSupport
import banghak.stock.support.web.ApiTestSupport.assertConforms
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 조건주문 REST 가 질의·의도를 usecase 에 넘기고 응답이 스키마에 맞음.
 * 증권사는 부르지 않음.
 */
class ConditionalOrderApiTest {
    private val now = Instant.parse("2026-10-05T02:00:00Z")
    private val listed = mutableListOf<Pair<UserId, ConditionalOrdersQuery>>()
    private val registered = mutableListOf<RegisterConditionalOrderRequest>()
    private val amended = mutableListOf<AmendConditionalOrderRequest>()
    private val canceled = mutableListOf<CancelConditionalOrderRequest>()
    private val listing =
        object : ListConditionalOrdersUseCase {
            override fun list(
                userId: UserId,
                query: ConditionalOrdersQuery,
            ): ConditionalOrdersPage {
                listed += userId to query
                return ConditionalOrdersPage(listOf(record()), "cursor-2")
            }
        }
    private val register =
        object : RegisterConditionalOrderUseCase {
            override fun register(request: RegisterConditionalOrderRequest): ConditionalPlacement {
                registered += request
                return ConditionalPlacement.Registered(request.clientOrderId, "C-1")
            }
        }
    private val amend =
        object : AmendConditionalOrderUseCase {
            override fun amend(request: AmendConditionalOrderRequest): ConditionalPlacement {
                amended += request
                return ConditionalPlacement.Pending(request.clientOrderId)
            }
        }
    private val cancel =
        object : CancelConditionalOrderUseCase {
            override fun cancel(
                request: CancelConditionalOrderRequest
            ): ConditionalCancelPlacement {
                canceled += request
                return ConditionalCancelPlacement.Canceled
            }
        }
    private val mvc =
        ApiTestSupport.mockMvc(
            ConditionalOrderController(
                listing,
                register,
                amend,
                cancel,
                Clock.fixed(now, ZoneOffset.UTC),
            )
        )

    @Test
    @DisplayName("목록은 범위·종목·커서를 세션 사용자로 묻고 한 쪽을 돌려줌")
    fun listsWithQuery() {
        val body =
            mvc.perform(
                    get("/conditional-orders")
                        .param("scope", "CLOSED")
                        .param("market", "US")
                        .param("code", "NVDA")
                        .param("cursor", "cursor-1")
                )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.conditionalOrders[0].conditionalOrderId").value("C-0"))
                .andExpect(jsonPath("$.conditionalOrders[0].first.isPriceTrigger").value(true))
                .andExpect(jsonPath("$.conditionalOrders[0].second").value(null))
                .andExpect(jsonPath("$.nextCursor").value("cursor-2"))
                .andReturn()
                .response
                .contentAsString

        assertThat(listed)
            .containsExactly(
                TradingFixtures.user to
                    ConditionalOrdersQuery(
                        ConditionalOrderScope.CLOSED,
                        TradingFixtures.nvidia,
                        "cursor-1",
                    )
            )
        assertConforms(body, "api-conditional-orders")
    }

    @Test
    @DisplayName("범위를 주지 않으면 열린 것만, 시장만 주고 코드를 빼면 400")
    fun listDefaultsAndHalfSymbol() {
        mvc.perform(get("/conditional-orders")).andExpect(status().isOk)
        assertThat(listed.single().second)
            .isEqualTo(ConditionalOrdersQuery(ConditionalOrderScope.OPEN, null, null))

        mvc.perform(get("/conditional-orders").param("market", "KR"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("InvalidValueException"))
    }

    @Test
    @DisplayName("OCO 등록 요청은 세션 사용자·디바이스·데몬 시계의 의도가 되어 등록 결과를 돌려줌")
    fun registersOco() {
        val body =
            mvc.perform(
                    post("/conditional-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OCO_REQUEST)
                )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("REGISTERED"))
                .andExpect(jsonPath("$.conditionalOrderId").value("C-1"))
                .andReturn()
                .response
                .contentAsString

        val request = registered.single()
        assertThat(request.clientOrderId).isEqualTo(ClientOrderId("c-1"))
        assertThat(request.confirmedRules).containsExactly("HighValue")
        with(request.intent) {
            assertThat(userId).isEqualTo(TradingFixtures.user)
            assertThat(symbol).isEqualTo(TradingFixtures.nvidia)
            assertThat(type).isEqualTo(ConditionalOrderType.OCO)
            assertThat(quantity).isEqualTo(Quantity.of(2))
            assertThat(first).isEqualTo(ConditionLeg(OrderSide.SELL, usd("150"), usd("149")))
            assertThat(second).isEqualTo(ConditionLeg(OrderSide.SELL, usd("100"), usd("99")))
            assertThat(expireDate).isEqualTo(LocalDate.of(2026, 10, 30))
            assertThat(requestedBy).isEqualTo(TradingFixtures.device)
            assertThat(intendedAt).isEqualTo(now)
        }
        assertConforms(body, "api-conditional-order-placement")
    }

    @Test
    @DisplayName("규격에 맞지 않는 조건(OCO 첫 감시가가 둘째보다 낮음)은 400 이고 usecase 를 부르지 않음")
    fun rejectsInvalidShape() {
        mvc.perform(
                post("/conditional-orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(OCO_REQUEST.replace("\"150\"", "\"90\""))
            )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("InvalidValueException"))

        assertThat(registered).isEmpty()
    }

    @Test
    @DisplayName("수정·취소는 경로의 조건주문 번호와 세션 사용자·디바이스로 요청을 만듦")
    fun amendAndCancel() {
        val amendBody =
            mvc.perform(
                    post("/conditional-orders/C-1/amend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OCO_REQUEST.replace("\"c-1\"", "\"c-2\""))
                )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.conditionalOrderId").value(null))
                .andReturn()
                .response
                .contentAsString
        assertConforms(amendBody, "api-conditional-order-placement")
        with(amended.single()) {
            assertThat(clientOrderId).isEqualTo(ClientOrderId("c-2"))
            assertThat(conditionalOrderId).isEqualTo("C-1")
            assertThat(intent.requestedBy).isEqualTo(TradingFixtures.device)
        }

        val cancelBody =
            mvc.perform(post("/conditional-orders/C-1/cancel"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("CANCELED"))
                .andReturn()
                .response
                .contentAsString
        assertConforms(cancelBody, "api-conditional-cancel")
        assertThat(canceled)
            .containsExactly(
                CancelConditionalOrderRequest(TradingFixtures.user, TradingFixtures.device, "C-1")
            )
    }

    private fun record() =
        ConditionalOrderRecord(
            conditionalOrderId = "C-0",
            type = ConditionalOrderType.SINGLE,
            status = ConditionalOrderStatus.WATCHING,
            symbol = TradingFixtures.nvidia,
            quantity = Quantity.of(1),
            kind = OrderKind.LIMIT,
            expireDate = LocalDate.of(2026, 10, 30),
            first =
                ConditionLegRecord(
                    isPriceTrigger = true,
                    status = ConditionLegStatus.WATCHING,
                    triggerPrice = usd("150"),
                    orderPrice = usd("149"),
                    triggeredOrderId = null,
                ),
            second = null,
            createdAt = now,
        )

    companion object {
        private const val OCO_REQUEST =
            """
            {"clientOrderId":"c-1","symbol":{"market":"US","code":"NVDA"},"type":"OCO","quantity":"2",
             "first":{"side":"SELL","triggerPrice":{"amount":"150","currency":"USD"},"orderPrice":{"amount":"149","currency":"USD"}},
             "second":{"side":"SELL","triggerPrice":{"amount":"100","currency":"USD"},"orderPrice":{"amount":"99","currency":"USD"}},
             "expireDate":"2026-10-30","confirmedRules":["HighValue"]}
            """
    }
}
