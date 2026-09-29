package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.OrderRejectedException
import banghak.stock.core.domain.error.OrderResultUnknownException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.ClosedOrdersQuery
import banghak.stock.core.domain.trading.OrderAmendRequest
import banghak.stock.core.domain.trading.OrderIntent
import banghak.stock.core.domain.trading.OrderKind
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.OrderStatus
import banghak.stock.core.domain.trading.OrderSubmission
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TimeInForce
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.engine.config.ExternalEndpointProperties
import banghak.stock.engine.config.TossHttpConfig
import banghak.stock.shared.config.HttpProperties
import banghak.stock.shared.config.OkHttpConfig
import banghak.stock.shared.config.RetrofitFactory
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.MemorySecretStore
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 토스 주문 어댑터를 WireMock 으로 검증함.
 * 응답 본문은 토스 규격(openapi.json v1.2.17)의 예시로 만든 것임.
 * 실제 주문 API 는 부르지 않음.
 */
class TossTradingAdapterTest {
    private val server = WireMockServer(wireMockConfig().dynamicPort())
    private val user = TradingFixtures.user
    private val clock = MutableClock(Instant.parse("2026-09-30T01:00:00Z"))
    private val secrets = MemorySecretStore()
    private lateinit var tokens: TossTokenCache
    private lateinit var adapter: TossTradingAdapter

    @BeforeEach
    fun setUp() {
        server.start()
        secrets.put(
            SecretKey.user(user, CredentialKind.TOSS.secretName("CLIENT_ID")),
            SecretValue.of("client-id-1"),
        )
        secrets.put(
            SecretKey.user(user, CredentialKind.TOSS.secretName("CLIENT_SECRET")),
            SecretValue.of("secret-1"),
        )
        val config = config(baseUrl(), HttpProperties())
        tokens = TossTokenCache(TossTokenIssuer(config.tossAuthClient(), secrets, clock), clock)
        adapter = adapterWith(config)
        json(
            post(urlPathEqualTo("/oauth2/token")),
            """{"access_token":"tok-1","token_type":"Bearer","expires_in":86400}""",
        )
        json(
            get(urlPathEqualTo("/api/v1/accounts")),
            """{"result":[{"accountNo":"12345678901","accountSeq":1,"accountType":"BROKERAGE"},{"accountNo":"9","accountSeq":2,"accountType":"PENSION_SAVINGS"}]}""",
        )
    }

    @AfterEach fun tearDown() = server.stop()

    @Test
    @DisplayName("국내 지정가 주문은 멱등 키·수량·가격을 문자열로, 계좌 순번을 헤더로 보내고 접수 번호를 돌려줌")
    fun placesKoreanLimitOrder() {
        json(
            post(urlPathEqualTo("/api/v1/orders")),
            """{"result":{"orderId":"ORD-1","clientOrderId":"KEY-1"}}""",
        )

        val receipt =
            adapter.placeOrder(
                OrderSubmission(
                    TradingFixtures.limitBuy(),
                    ClientOrderId("KEY-1"),
                    isHighValueConfirmed = false,
                )
            )

        assertThat(receipt.brokerOrderId).isEqualTo("ORD-1")
        assertThat(receipt.clientOrderId).isEqualTo(ClientOrderId("KEY-1"))
        server.verify(
            postRequestedFor(urlPathEqualTo("/api/v1/orders"))
                .withHeader(ACCOUNT_HEADER, equalTo("1"))
                .withHeader("Authorization", equalTo("Bearer tok-1"))
                .withRequestBody(
                    equalToJson(
                        """{"clientOrderId":"KEY-1","symbol":"005930","side":"BUY","orderType":"LIMIT","timeInForce":"DAY","quantity":"10","price":"70000"}"""
                    )
                )
        )
    }

    @Test
    @DisplayName("미국 금액 시장가 매수는 유효 조건·수량·가격 없이 주문 금액만 보내고, 고액 확인은 true 로 실음")
    fun placesUsAmountOrderWithHighValueConfirmation() {
        json(
            post(urlPathEqualTo("/api/v1/orders")),
            """{"result":{"orderId":"ORD-2","clientOrderId":"KEY-2"}}""",
        )
        val intent =
            OrderIntent(
                user,
                TradingFixtures.nvidia,
                OrderSide.BUY,
                OrderKind.MARKET,
                TimeInForce.DAY,
                null,
                null,
                TradingFixtures.usd("500.00"),
                OrderOrigin.MANUAL,
                TradingFixtures.manual,
                clock.instant(),
            )

        adapter.placeOrder(
            OrderSubmission(intent, ClientOrderId("KEY-2"), isHighValueConfirmed = true)
        )

        server.verify(
            postRequestedFor(urlPathEqualTo("/api/v1/orders"))
                .withRequestBody(
                    equalToJson(
                        """{"clientOrderId":"KEY-2","symbol":"NVDA","side":"BUY","orderType":"MARKET","orderAmount":"500.00","confirmHighValueOrder":true}"""
                    )
                )
        )
    }

    @Test
    @DisplayName("보낸 뒤 응답이 늦으면 결과 모름으로 올리고 주문 POST 를 다시 보내지 않음")
    fun readTimeoutIsUnknownAndNeverRetried() {
        server.stubFor(
            post(urlPathEqualTo("/api/v1/orders"))
                .willReturn(aResponse().withFixedDelay(1500).withStatus(200))
        )
        val slow =
            adapterWith(config(baseUrl(), HttpProperties(readTimeout = Duration.ofMillis(300))))

        assertThatThrownBy { slow.placeOrder(submission()) }
            .isInstanceOf(OrderResultUnknownException::class.java)
        server.verify(1, postRequestedFor(urlPathEqualTo("/api/v1/orders")))
    }

    @Test
    @DisplayName("연결 자체가 안 되면 주문이 나가지 않은 것이므로 증권사 연결 실패로 올림")
    fun connectionRefusedMeansNotPlaced() {
        val deadOrders = config("http://127.0.0.1:1/", HttpProperties()).tossOrderClient(tokens)
        val adapter =
            TossTradingAdapter(
                deadOrders,
                config(baseUrl(), HttpProperties()).tossAccountClient(tokens),
                TossAccountCache(
                    TossAccountLookup(config(baseUrl(), HttpProperties()).tossAccountClient(tokens))
                ),
                clock,
            )

        assertThatThrownBy { adapter.placeOrder(submission()) }
            .isInstanceOf(BrokerUnavailableException::class.java)
    }

    @Test
    @DisplayName("400 호가 단위 오류는 거부 사유와 함께 호가 단위·가까운 가격을 실어 올림")
    fun tickSizeRejectionCarriesSuggestions() {
        error(
            post(urlPathEqualTo("/api/v1/orders")),
            400,
            "invalid-request",
            """{"field":"price","tickSize":"100","nearestPrices":["50100","50200"]}""",
        )

        assertThatThrownBy { adapter.placeOrder(submission()) }
            .isInstanceOfSatisfying(OrderRejectedException::class.java) {
                assertThat(it.tickSize).isEqualByComparingTo(BigDecimal("100"))
                assertThat(it.nearestPrices)
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactly(BigDecimal("50100"), BigDecimal("50200"))
            }
    }

    @Test
    @DisplayName("같은 주문 처리 중(409)·5xx 는 결과 모름, 반대 방향 미체결(409)·422 는 거부, 429 는 주문 안 됨")
    fun classifiesOrderFailures() {
        val cases =
            listOf(
                Triple(409, "request-in-progress", OrderResultUnknownException::class.java),
                Triple(500, "internal-error", OrderResultUnknownException::class.java),
                Triple(409, "opposite-pending-order-exists", OrderRejectedException::class.java),
                Triple(422, "insufficient-buying-power", OrderRejectedException::class.java),
                Triple(429, "rate-limit-exceeded", BrokerUnavailableException::class.java),
            )
        for ((status, code, expected) in cases) {
            error(post(urlPathEqualTo("/api/v1/orders")), status, code, "null")
            assertThatThrownBy { adapter.placeOrder(submission()) }
                .describedAs("$status $code")
                .isInstanceOf(expected)
        }
    }

    @Test
    @DisplayName("국내 정정은 지정가로 가격과 수량을 함께, 미국 정정은 가격만 보내고 새 주문 번호를 돌려줌")
    fun amendsWithMarketRules() {
        json(
            post(urlPathEqualTo("/api/v1/orders/ORD-1/modify")),
            """{"result":{"orderId":"ORD-1B"}}""",
        )
        json(
            post(urlPathEqualTo("/api/v1/orders/ORD-9/modify")),
            """{"result":{"orderId":"ORD-9B"}}""",
        )

        val kr =
            adapter.placeAmendment(
                OrderAmendRequest(
                    user,
                    "ORD-1",
                    TradingFixtures.samsung,
                    TradingFixtures.krw("71000"),
                    Quantity.of(76),
                    isHighValueConfirmed = false,
                )
            )
        adapter.placeAmendment(
            OrderAmendRequest(
                user,
                "ORD-9",
                TradingFixtures.nvidia,
                TradingFixtures.usd("120.50"),
                null,
                isHighValueConfirmed = false,
            )
        )

        assertThat(kr.brokerOrderId).isEqualTo("ORD-1B")
        server.verify(
            postRequestedFor(urlPathEqualTo("/api/v1/orders/ORD-1/modify"))
                .withRequestBody(
                    equalToJson("""{"orderType":"LIMIT","quantity":"76","price":"71000"}""")
                )
        )
        server.verify(
            postRequestedFor(urlPathEqualTo("/api/v1/orders/ORD-9/modify"))
                .withRequestBody(equalToJson("""{"orderType":"LIMIT","price":"120.50"}"""))
        )
        assertThatThrownBy {
                OrderAmendRequest(
                    user,
                    "ORD-1",
                    TradingFixtures.samsung,
                    TradingFixtures.krw("71000"),
                    null,
                    false,
                )
            }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy {
                OrderAmendRequest(
                    user,
                    "ORD-9",
                    TradingFixtures.nvidia,
                    TradingFixtures.usd("1.00"),
                    Quantity.of(1),
                    false,
                )
            }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("취소는 빈 본문으로 보내고 취소 요청 레코드의 새 주문 번호를 돌려줌")
    fun cancels() {
        json(
            post(urlPathEqualTo("/api/v1/orders/ORD-1/cancel")),
            """{"result":{"orderId":"ORD-1C"}}""",
        )

        assertThat(adapter.cancelOrder(user, "ORD-1").brokerOrderId).isEqualTo("ORD-1C")
        server.verify(
            postRequestedFor(urlPathEqualTo("/api/v1/orders/ORD-1/cancel"))
                .withRequestBody(equalToJson("{}"))
        )
    }

    @Test
    @DisplayName("주문 상세는 체결 요약까지 옮기고, 토스 상태 10개와 모르는 값을 도메인 상태로 바꿈")
    fun mapsOrderRecordAndStatuses() {
        json(
            get(urlPathEqualTo("/api/v1/orders/ORD-1")),
            """{"result":${order("ORD-1", "005930", "PARTIAL_FILLED", "KRW")}}""",
        )

        val record = adapter.lookupOrder(user, "ORD-1")

        assertThat(record.symbol).isEqualTo(Symbol(Market.KR, "005930"))
        assertThat(record.status).isEqualTo(OrderStatus.PARTIALLY_FILLED)
        assertThat(record.filledQuantity).isEqualTo(Quantity.of(4))
        assertThat(record.averageFilledPrice).isEqualTo(Money.of("70000", Currency.KRW))
        assertThat(record.fee).isEqualTo(Money.of("560", Currency.KRW))
        val table =
            mapOf(
                "PENDING" to OrderStatus.PENDING,
                "PARTIAL_FILLED" to OrderStatus.PARTIALLY_FILLED,
                "PENDING_CANCEL" to OrderStatus.PENDING_CANCEL,
                "PENDING_REPLACE" to OrderStatus.PENDING_AMEND,
                "FILLED" to OrderStatus.FILLED,
                "CANCELED" to OrderStatus.CANCELLED,
                "REJECTED" to OrderStatus.REJECTED,
                "CANCEL_REJECTED" to OrderStatus.CANCEL_REJECTED,
                "REPLACE_REJECTED" to OrderStatus.AMEND_REJECTED,
                "REPLACED" to OrderStatus.REPLACED,
                "SOMETHING_NEW" to OrderStatus.UNKNOWN,
            )
        table.forEach { (toss, domain) ->
            assertThat(TossOrderMapping.statusOf(toss)).describedAs(toss).isEqualTo(domain)
        }
    }

    @Test
    @DisplayName("미체결은 시장별로 거르고, 종료 주문은 기간·커서·100건 페이지로 부르며 다음 커서를 돌려줌")
    fun listsOpenAndClosedOrders() {
        json(
            get(urlPathEqualTo("/api/v1/orders")).withQueryParam("status", equalTo("OPEN")),
            """{"result":{"orders":[${order("A", "005930", "PENDING", "KRW")},${order("B", "NVDA", "PENDING", "USD")}],"nextCursor":null,"hasNext":false}}""",
        )
        json(
            get(urlPathEqualTo("/api/v1/orders")).withQueryParam("status", equalTo("CLOSED")),
            """{"result":{"orders":[${order("C", "005930", "FILLED", "KRW")}],"nextCursor":"CUR-2","hasNext":true}}""",
        )

        assertThat(adapter.openOrders(user, Market.US).map { it.brokerOrderId })
            .containsExactly("B")
        val page =
            adapter.closedOrders(
                user,
                ClosedOrdersQuery(
                    Market.KR,
                    LocalDate.of(2026, 9, 1),
                    LocalDate.of(2026, 9, 30),
                    "CUR-1",
                ),
            )

        assertThat(page.orders.map { it.brokerOrderId }).containsExactly("C")
        assertThat(page.nextCursor).isEqualTo("CUR-2")
        server.verify(
            getRequestedFor(urlPathEqualTo("/api/v1/orders"))
                .withQueryParam("from", equalTo("2026-09-01"))
                .withQueryParam("to", equalTo("2026-09-30"))
                .withQueryParam("cursor", equalTo("CUR-1"))
                .withQueryParam("limit", equalTo("100"))
        )
    }

    @Test
    @DisplayName("보유는 종목 통화 금액과 원화 평가 합계로, 매수 가능 금액은 요청 통화가 맞을 때만 돌려줌")
    fun mapsHoldingsAndBuyingPower() {
        json(
            get(urlPathEqualTo("/api/v1/holdings")),
            """{"result":{"marketValue":{"amount":{"krw":"7200000","usd":null}},"items":[{"symbol":"005930","marketCountry":"KR","currency":"KRW","quantity":"100","lastPrice":"72000","averagePurchasePrice":"65000","marketValue":{"purchaseAmount":"6500000","amount":"7200000"},"profitLoss":{"amount":"700000"}}]}}""",
        )
        json(
            get(urlPathEqualTo("/api/v1/buying-power")).withQueryParam("currency", equalTo("KRW")),
            """{"result":{"currency":"KRW","cashBuyingPower":"5000000"}}""",
        )
        json(
            get(urlPathEqualTo("/api/v1/buying-power")).withQueryParam("currency", equalTo("USD")),
            """{"result":{"currency":"KRW","cashBuyingPower":"1"}}""",
        )

        val holdings = adapter.holdings(user)

        assertThat(holdings.items.single().marketValue).isEqualTo(Money.of("7200000", Currency.KRW))
        assertThat(holdings.marketValueKrw).isEqualTo(Money.of("7200000", Currency.KRW))
        assertThat(adapter.buyingPower(user, Currency.KRW))
            .isEqualTo(Money.of("5000000", Currency.KRW))
        assertThatThrownBy { adapter.buyingPower(user, Currency.USD) }
            .isInstanceOf(BrokerUnavailableException::class.java)
    }

    @Test
    @DisplayName("계좌 순번은 종합매매 계좌 하나를 골라 한 번만 조회하고, 여럿이면 선택이 필요하다고 알림")
    fun resolvesBrokerageAccountOnce() {
        json(
            post(urlPathEqualTo("/api/v1/orders")),
            """{"result":{"orderId":"ORD-1","clientOrderId":null}}""",
        )

        adapter.placeOrder(submission())
        adapter.placeOrder(submission())

        server.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/accounts")))
        json(
            get(urlPathEqualTo("/api/v1/accounts")),
            """{"result":[{"accountSeq":1,"accountType":"BROKERAGE"},{"accountSeq":3,"accountType":"BROKERAGE"}]}""",
        )
        assertThatThrownBy {
                TossAccountLookup(config(baseUrl(), HttpProperties()).tossAccountClient(tokens))
                    .brokerageAccountSeq(TossCaller(user))
            }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("돈이 걸린 경로의 fallback 은 서킷·한도로 막힌 요청을 주문 안 됨으로, 알 수 없는 오류를 결과 모름으로 봄")
    fun mutationFallbackIsFailSafe() {
        val open =
            CallNotPermittedException.createCallNotPermittedException(
                io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("t")
            )
        val limited = RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("r"))

        assertThatThrownBy { adapter.placeOrderFailed(submission(), open) }
            .isInstanceOf(BrokerUnavailableException::class.java)
        assertThatThrownBy { adapter.placeOrderFailed(submission(), limited) }
            .isInstanceOf(BrokerUnavailableException::class.java)
        assertThatThrownBy { adapter.placeOrderFailed(submission(), IllegalStateException("x")) }
            .isInstanceOf(OrderResultUnknownException::class.java)
    }

    private fun submission() =
        OrderSubmission(
            TradingFixtures.limitBuy(),
            ClientOrderId("KEY-1"),
            isHighValueConfirmed = false,
        )

    private fun baseUrl() = "http://127.0.0.1:${server.port()}/"

    private fun config(base: String, http: HttpProperties): TossHttpConfig {
        val shared = OkHttpConfig().okHttpClient(http)
        return TossHttpConfig(
            RetrofitFactory(shared),
            ExternalEndpointProperties(tossBaseUrl = base),
            shared,
        )
    }

    private fun adapterWith(config: TossHttpConfig): TossTradingAdapter =
        TossTradingAdapter(
            config.tossOrderClient(tokens),
            config.tossAccountClient(tokens),
            TossAccountCache(TossAccountLookup(config.tossAccountClient(tokens))),
            clock,
        )

    private fun json(request: com.github.tomakehurst.wiremock.client.MappingBuilder, body: String) {
        server.stubFor(
            request.willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)
            )
        )
    }

    private fun error(
        request: com.github.tomakehurst.wiremock.client.MappingBuilder,
        status: Int,
        code: String,
        data: String,
    ) {
        server.stubFor(
            request.willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """{"error":{"requestId":"r","code":"$code","message":"m","data":$data}}"""
                    )
            )
        )
    }

    private fun order(id: String, symbol: String, status: String, currency: String): String {
        val price = if (currency == "KRW") "70000" else "120.5"
        return """{"orderId":"$id","symbol":"$symbol","side":"BUY","orderType":"LIMIT","timeInForce":"DAY","status":"$status","price":"$price","quantity":"10","orderAmount":null,"currency":"$currency","orderedAt":"2026-09-30T09:30:00.000+09:00","canceledAt":null,"execution":{"filledQuantity":"4","averageFilledPrice":"$price","filledAmount":"280000","commission":"560","tax":"0","filledAt":"2026-09-30T09:31:15.000+09:00","settlementDate":"2026-10-02"}}"""
    }
}
