package banghak.stock.engine.adapter.`in`.web

import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.error.ConfirmationRequiredException
import banghak.stock.core.domain.error.GuardrailViolationException
import banghak.stock.core.domain.error.OrderRejectedException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Percent
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.ManualTrigger
import banghak.stock.core.domain.trading.OrderKind
import banghak.stock.core.domain.trading.OrderListing
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.OrderStatus
import banghak.stock.core.domain.trading.OrderTicket
import banghak.stock.core.domain.trading.PriceLimits
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TimeInForce
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.domain.trading.TradingFixtures.usd
import banghak.stock.core.usecase.AmendOrderRequest
import banghak.stock.core.usecase.AmendOrderUseCase
import banghak.stock.core.usecase.CancelOrderRequest
import banghak.stock.core.usecase.CancelOrderUseCase
import banghak.stock.core.usecase.CancelPlacement
import banghak.stock.core.usecase.ListOrdersUseCase
import banghak.stock.core.usecase.LookupOrderTicketUseCase
import banghak.stock.core.usecase.ManualOrderRequest
import banghak.stock.core.usecase.OrderPlacement
import banghak.stock.core.usecase.PlaceManualOrderUseCase
import banghak.stock.engine.adapter.`in`.web.order.OrderController
import banghak.stock.support.web.ApiTestSupport
import banghak.stock.support.web.ApiTestSupport.assertConforms
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
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
 * 수동 주문 REST 가 요청을 세션 사용자·디바이스의 주문 의도로 바꾸고, 주문 경로의 예외를 화면이 쓸 상세와 함께 돌려줌.
 * 증권사는 부르지 않음(usecase 가짜).
 */
class OrderApiTest {
    private val now = Instant.parse("2026-10-05T02:00:00Z")
    private val placed = mutableListOf<ManualOrderRequest>()
    private val amended = mutableListOf<AmendOrderRequest>()
    private val canceled = mutableListOf<CancelOrderRequest>()
    private var placement: () -> OrderPlacement = {
        OrderPlacement.Accepted(ClientOrderId("01J9ZK0000000000000000000A"), "B-1")
    }
    private val tickets =
        object : LookupOrderTicketUseCase {
            override fun ticket(userId: UserId, symbol: Symbol) =
                OrderTicket(
                    symbol,
                    krw("1000000"),
                    Quantity.of(10),
                    PriceLimits(symbol, krw("91000"), krw("49000"), now),
                    Percent.ofRatio("0.00015"),
                )
        }
    private val placeOrder =
        object : PlaceManualOrderUseCase {
            override fun place(request: ManualOrderRequest): OrderPlacement {
                placed += request
                return placement()
            }
        }
    private val amendOrder =
        object : AmendOrderUseCase {
            override fun amend(request: AmendOrderRequest): OrderPlacement {
                amended += request
                return placement()
            }
        }
    private val cancelOrder =
        object : CancelOrderUseCase {
            override fun cancel(request: CancelOrderRequest): CancelPlacement {
                canceled += request
                return CancelPlacement.Requested("B-2")
            }
        }
    private val listing =
        OrderListing(
            brokerOrderId = "B-1",
            replacesBrokerOrderId = null,
            symbol = TradingFixtures.samsung,
            side = OrderSide.BUY,
            kind = OrderKind.LIMIT,
            timeInForce = TimeInForce.DAY,
            limitPrice = krw("74200"),
            quantity = Quantity.of(10),
            orderAmount = null,
            status = OrderStatus.PARTIALLY_FILLED,
            filledQuantity = Quantity.of(4),
            averageFilledPrice = krw("74200"),
            filledAmount = krw("296800"),
            origin = OrderOrigin.MANUAL,
            isPlacedByStockholm = true,
            orderedAt = now,
            updatedAt = now,
            closedAt = null,
        )
    private val listOrders =
        object : ListOrdersUseCase {
            override fun openOrders(userId: UserId) =
                listOf(
                    listing,
                    listing.copy(
                        brokerOrderId = "B-M",
                        kind = OrderKind.MARKET,
                        limitPrice = null,
                        quantity = null,
                        orderAmount = usd("100.00"),
                        symbol = TradingFixtures.nvidia,
                    ),
                )

            override fun todayClosedOrders(userId: UserId) =
                listOf(
                    listing.copy(
                        brokerOrderId = "B-0",
                        status = OrderStatus.FILLED,
                        filledQuantity = Quantity.of(10),
                    )
                )
        }
    private val mvc =
        ApiTestSupport.mockMvc(
            OrderController(
                tickets,
                placeOrder,
                amendOrder,
                cancelOrder,
                listOrders,
                Clock.fixed(now, ZoneOffset.UTC),
            )
        )

    @Test
    @DisplayName("미체결·오늘 체결 목록은 잔량과 정정 가능 여부를 실어 주고 스키마에 맞음")
    fun openAndTodayListings() {
        val open =
            mvc.perform(get("/orders/open"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].remaining").value("6"))
                .andExpect(jsonPath("$[0].canAmend").value(true))
                .andExpect(jsonPath("$[0].canCancel").value(true))
                .andExpect(jsonPath("$[0].status").value("PARTIALLY_FILLED"))
                .andExpect(jsonPath("$[1].canAmend").value(false))
                .andExpect(jsonPath("$[1].canCancel").value(true))
                .andReturn()
                .response
                .contentAsString
        assertConforms(open.removePrefix("[").removeSuffix("]"), "api-order-listing")

        val today =
            mvc.perform(get("/orders/today"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].brokerOrderId").value("B-0"))
                .andExpect(jsonPath("$[0].canAmend").value(false))
                .andExpect(jsonPath("$[0].canCancel").value(false))
                .andReturn()
                .response
                .contentAsString
        assertConforms(today.removePrefix("[").removeSuffix("]"), "api-order-listing")
    }

    @Test
    @DisplayName("주문 가능 정보는 매수 가능 금액·판매 가능 수량·상하한가·수수료율을 돌려줌")
    fun ticket() {
        val body =
            mvc.perform(get("/orders/ticket").param("market", "KR").param("code", "005930"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.sellableQuantity").value("10"))
                .andExpect(jsonPath("$.priceLimits.upper.amount").value("91000"))
                .andExpect(jsonPath("$.commissionRate").value("0.00015"))
                .andReturn()
                .response
                .contentAsString
        assertConforms(body, "api-order-ticket")
    }

    @Test
    @DisplayName("지정가 주문 요청은 세션 사용자·디바이스·데몬 시계의 수동 의도가 되어 접수 결과를 돌려줌")
    fun placesLimitOrder() {
        val body =
            mvc.perform(
                    post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"clientOrderId":"01J9ZK0000000000000000000A",
                             "symbol":{"market":"KR","code":"005930"},
                             "side":"BUY","kind":"LIMIT",
                             "limitPrice":{"amount":"70000","currency":"KRW"},
                             "quantity":"3",
                             "confirmedRules":["HighValue"]}
                            """
                        )
                )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("ACCEPTED"))
                .andExpect(jsonPath("$.brokerOrderId").value("B-1"))
                .andReturn()
                .response
                .contentAsString

        val request = placed.single()
        assertThat(request.clientOrderId).isEqualTo(ClientOrderId("01J9ZK0000000000000000000A"))
        assertThat(request.confirmedRules).containsExactly("HighValue")
        with(request.intent) {
            assertThat(userId).isEqualTo(TradingFixtures.user)
            assertThat(symbol).isEqualTo(TradingFixtures.samsung)
            assertThat(side).isEqualTo(OrderSide.BUY)
            assertThat(kind).isEqualTo(OrderKind.LIMIT)
            assertThat(timeInForce).isEqualTo(TimeInForce.DAY)
            assertThat(limitPrice).isEqualTo(krw("70000"))
            assertThat(quantity).isEqualTo(Quantity.of(3))
            assertThat(origin).isEqualTo(OrderOrigin.MANUAL)
            assertThat(trigger).isEqualTo(ManualTrigger(TradingFixtures.device))
            assertThat(intendedAt).isEqualTo(now)
        }
        assertConforms(body, "api-order-placement")
    }

    @Test
    @DisplayName("미국 금액 매수는 시장가·주문 금액으로 의도가 만들어짐")
    fun placesUsAmountOrder() {
        mvc.perform(
                post("/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"clientOrderId":"k-2","symbol":{"market":"US","code":"NVDA"},
                         "side":"BUY","kind":"MARKET",
                         "orderAmount":{"amount":"100.00","currency":"USD"}}
                        """
                    )
            )
            .andExpect(status().isOk)

        assertThat(placed.single().intent.orderAmount).isEqualTo(usd("100.00"))
        assertThat(placed.single().intent.quantity).isNull()
    }

    @Test
    @DisplayName("규격에 맞지 않는 주문(국내 시장가)은 400 이고 usecase 를 부르지 않음")
    fun rejectsInvalidShapeBeforeUseCase() {
        mvc.perform(
                post("/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"clientOrderId":"k-3","symbol":{"market":"KR","code":"005930"},"side":"BUY","kind":"MARKET","orderAmount":{"amount":"1","currency":"KRW"}}"""
                    )
            )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("InvalidValueException"))

        assertThat(placed).isEmpty()
    }

    @Test
    @DisplayName("가드레일 거부는 422 에 위반 목록, 확인 요구는 428 에 노트, 증권사 거부는 호가 제안을 실음")
    fun orderPathErrorsCarryDetails() {
        placement = { throw GuardrailViolationException(listOf("DailyLimit: 하루 한도 초과")) }
        val violation =
            mvc.perform(
                    post("/orders").contentType(MediaType.APPLICATION_JSON).content(LIMIT_ORDER)
                )
                .andExpect(status().isUnprocessableContent)
                .andExpect(jsonPath("$.code").value("GuardrailViolationException"))
                .andExpect(jsonPath("$.violations[0]").value("DailyLimit: 하루 한도 초과"))
                .andReturn()
                .response
                .contentAsString
        assertConforms(violation, "api-error")

        placement = { throw ConfirmationRequiredException(listOf("HighValue: 1억원 이상")) }
        val confirmation =
            mvc.perform(
                    post("/orders").contentType(MediaType.APPLICATION_JSON).content(LIMIT_ORDER)
                )
                .andExpect(status().isPreconditionRequired)
                .andExpect(jsonPath("$.notes[0]").value("HighValue: 1억원 이상"))
                .andReturn()
                .response
                .contentAsString
        assertConforms(confirmation, "api-error")

        placement = {
            throw OrderRejectedException(
                "호가 단위 불일치",
                BigDecimal("100"),
                listOf(BigDecimal("70000"), BigDecimal("70100")),
            )
        }
        val rejected =
            mvc.perform(
                    post("/orders").contentType(MediaType.APPLICATION_JSON).content(LIMIT_ORDER)
                )
                .andExpect(status().isUnprocessableContent)
                .andExpect(jsonPath("$.tickSize").value("100"))
                .andExpect(jsonPath("$.nearestPrices[1]").value("70100"))
                .andReturn()
                .response
                .contentAsString
        assertConforms(rejected, "api-error")

        placement = { throw BrokerUnavailableException("닿지 않음") }
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(LIMIT_ORDER))
            .andExpect(status().isServiceUnavailable)
    }

    @Test
    @DisplayName("정정·취소는 경로의 주문 번호와 세션 사용자·디바이스로 요청을 만듦")
    fun amendAndCancel() {
        val amend =
            mvc.perform(
                    post("/orders/B-1/amend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"clientOrderId":"k-9","newLimitPrice":{"amount":"69000","currency":"KRW"},"newQuantity":"2"}"""
                        )
                )
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
        assertConforms(amend, "api-order-placement")
        with(amended.single()) {
            assertThat(clientOrderId).isEqualTo(ClientOrderId("k-9"))
            assertThat(userId).isEqualTo(TradingFixtures.user)
            assertThat(deviceId).isEqualTo(TradingFixtures.device)
            assertThat(brokerOrderId).isEqualTo("B-1")
            assertThat(amendment.newLimitPrice).isEqualTo(krw("69000"))
            assertThat(amendment.newQuantity).isEqualTo(Quantity.of(2))
        }

        val cancel =
            mvc.perform(post("/orders/B-1/cancel"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("REQUESTED"))
                .andExpect(jsonPath("$.cancelBrokerOrderId").value("B-2"))
                .andReturn()
                .response
                .contentAsString
        assertConforms(cancel, "api-cancel-placement")
        assertThat(canceled)
            .containsExactly(
                CancelOrderRequest(TradingFixtures.user, TradingFixtures.device, "B-1")
            )
    }

    @Test
    @DisplayName("본문이 JSON 이 아니면 400 이고 같은 오류 모양임")
    fun malformedBody() {
        val body =
            mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("MalformedRequest"))
                .andReturn()
                .response
                .contentAsString
        assertConforms(body, "api-error")
    }

    companion object {
        private const val LIMIT_ORDER =
            """{"clientOrderId":"k-1","symbol":{"market":"KR","code":"005930"},"side":"BUY","kind":"LIMIT","limitPrice":{"amount":"70000","currency":"KRW"},"quantity":"1"}"""
    }
}
