package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.OrderRejectedException
import banghak.stock.core.domain.error.OrderResultUnknownException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.ConditionLegStatus
import banghak.stock.core.domain.trading.ConditionalFixtures
import banghak.stock.core.domain.trading.ConditionalOrderIntent
import banghak.stock.core.domain.trading.ConditionalOrderScope
import banghak.stock.core.domain.trading.ConditionalOrderStatus
import banghak.stock.core.domain.trading.ConditionalOrderSubmission
import banghak.stock.core.domain.trading.ConditionalOrderType
import banghak.stock.core.domain.trading.ConditionalOrdersQuery
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.engine.config.ExternalEndpointProperties
import banghak.stock.engine.config.TossHttpConfig
import banghak.stock.shared.config.HttpProperties
import banghak.stock.shared.config.OkHttpConfig
import banghak.stock.shared.config.RetrofitFactory
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.MemorySecretStore
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
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
 * 토스 조건주문 어댑터를 WireMock 으로 검증함.
 * 응답 본문은 토스 규격(openapi.json v1.2.19)의 예시로 만든 것임.
 * 실제 조건주문 API 는 부르지 않음.
 */
class TossConditionalOrderAdapterTest {
    private val server = WireMockServer(wireMockConfig().dynamicPort())
    private val user = TradingFixtures.user
    private val clock = MutableClock(Instant.parse("2026-09-30T01:00:00Z"))
    private val secrets = MemorySecretStore()
    private lateinit var tokens: TossTokenCache
    private lateinit var adapter: TossConditionalOrderAdapter

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
        val config = config(HttpProperties())
        tokens = TossTokenCache(TossTokenIssuer(config.tossAuthClient(), secrets, clock), clock)
        adapter = adapterWith(config)
        json(
            post(urlPathEqualTo("/oauth2/token")),
            """{"access_token":"tok-1","token_type":"Bearer","expires_in":86400}""",
        )
        json(
            get(urlPathEqualTo("/api/v1/accounts")),
            """{"result":[{"accountNo":"12345678901","accountSeq":1,"accountType":"BROKERAGE"}]}""",
        )
    }

    @AfterEach fun tearDown() = server.stop()

    @Test
    @DisplayName("OCO 등록은 멱등 키·지정가·두 조건·만료일을 보내고 조건주문 번호를 돌려줌")
    fun registersOco() {
        json(
            post(urlPathEqualTo("/api/v1/conditional-orders")),
            """{"result":{"conditionalOrderId":"gaZIG-dYMWil8AAXyPmlRg","clientOrderId":"KEY-1"}}""",
        )

        val id = adapter.placeConditionalOrder(submission(ConditionalFixtures.oco()))

        assertThat(id).isEqualTo("gaZIG-dYMWil8AAXyPmlRg")
        server.verify(
            postRequestedFor(urlPathEqualTo("/api/v1/conditional-orders"))
                .withHeader(ACCOUNT_HEADER, equalTo("1"))
                .withRequestBody(
                    equalToJson(
                        """{"symbol":"005930","type":"OCO","quantity":"10","orderType":"LIMIT","clientOrderId":"KEY-1","expireDate":"2026-10-30",
                           "first":{"orderSide":"SELL","triggerPrice":"80000","orderPrice":"80000"},
                           "second":{"orderSide":"SELL","triggerPrice":"60000","orderPrice":"60000"}}"""
                    )
                )
        )
    }

    @Test
    @DisplayName("SINGLE 은 둘째 조건 없이 보내고, 고액 확인은 true 로 실음")
    fun registersSingleWithHighValueConfirmation() {
        json(
            post(urlPathEqualTo("/api/v1/conditional-orders")),
            """{"result":{"conditionalOrderId":"C-2","clientOrderId":"KEY-1"}}""",
        )
        val single =
            ConditionalFixtures.single(
                trigger = TradingFixtures.usd("120.5"),
                price = TradingFixtures.usd("121.00"),
                symbol = TradingFixtures.nvidia,
            )

        adapter.placeConditionalOrder(submission(single, isHighValueConfirmed = true))

        server.verify(
            postRequestedFor(urlPathEqualTo("/api/v1/conditional-orders"))
                .withRequestBody(
                    equalToJson(
                        """{"symbol":"NVDA","type":"SINGLE","quantity":"10","orderType":"LIMIT","clientOrderId":"KEY-1","expireDate":"2026-10-30",
                           "first":{"orderSide":"BUY","triggerPrice":"120.50","orderPrice":"121.00"},"confirmHighValueOrder":true}"""
                    )
                )
        )
    }

    @Test
    @DisplayName("수정은 종목·멱등 키 없이 전체를 다시 보내고 새 번호를 돌려줌")
    fun modifiesWithFullBody() {
        json(
            post(urlPathEqualTo("/api/v1/conditional-orders/C-1/modify")),
            """{"result":{"conditionalOrderId":"C-9"}}""",
        )

        val id = adapter.placeConditionalAmendment("C-1", submission(ConditionalFixtures.oco()))

        assertThat(id).isEqualTo("C-9")
        server.verify(
            postRequestedFor(urlPathEqualTo("/api/v1/conditional-orders/C-1/modify"))
                .withRequestBody(
                    equalToJson(
                        """{"type":"OCO","quantity":"10","orderType":"LIMIT","expireDate":"2026-10-30",
                           "first":{"orderSide":"SELL","triggerPrice":"80000","orderPrice":"80000"},
                           "second":{"orderSide":"SELL","triggerPrice":"60000","orderPrice":"60000"}}"""
                    )
                )
        )
    }

    @Test
    @DisplayName("취소는 204 를 성공으로 받고, 없는 조건주문 404 는 거부로 올림")
    fun cancels() {
        server.stubFor(
            delete(urlPathEqualTo("/api/v1/conditional-orders/C-1"))
                .willReturn(aResponse().withStatus(204))
        )
        error(
            delete(urlPathEqualTo("/api/v1/conditional-orders/C-404")),
            404,
            "conditional-order-not-found",
        )

        adapter.cancelConditionalOrder(user, "C-1")

        server.verify(deleteRequestedFor(urlPathEqualTo("/api/v1/conditional-orders/C-1")))
        assertThatThrownBy { adapter.cancelConditionalOrder(user, "C-404") }
            .isInstanceOf(OrderRejectedException::class.java)
    }

    @Test
    @DisplayName("422 규칙 위반은 토스 오류 코드와 함께 거부로 올림")
    fun businessRuleViolationIsRejection() {
        error(
            post(urlPathEqualTo("/api/v1/conditional-orders")),
            422,
            "duplicate-conditional-order",
        )

        assertThatThrownBy { adapter.placeConditionalOrder(submission(ConditionalFixtures.oco())) }
            .isInstanceOf(OrderRejectedException::class.java)
            .hasMessageContaining("duplicate-conditional-order")
    }

    @Test
    @DisplayName("보낸 뒤 응답이 늦으면 결과 모름으로 올리고 등록 POST 를 다시 보내지 않음")
    fun readTimeoutIsUnknownAndNeverRetried() {
        server.stubFor(
            post(urlPathEqualTo("/api/v1/conditional-orders"))
                .willReturn(aResponse().withFixedDelay(1500).withStatus(200))
        )
        val slow = adapterWith(config(HttpProperties(readTimeout = Duration.ofMillis(300))))

        assertThatThrownBy { slow.placeConditionalOrder(submission(ConditionalFixtures.oco())) }
            .isInstanceOf(OrderResultUnknownException::class.java)
        server.verify(1, postRequestedFor(urlPathEqualTo("/api/v1/conditional-orders")))
    }

    @Test
    @DisplayName("목록은 범위·종목·최대 100건으로 묻고, 가격이 아닌 조건·SINGLE·다음 쪽을 그대로 옮김")
    fun listsAndMaps() {
        json(
            get(urlPathEqualTo("/api/v1/conditional-orders")),
            """{"result":{"conditionalOrders":[
                 {"conditionalOrderId":"C-1","type":"OCO","status":"WATCHING","symbol":"005930","market":"KR","quantity":"10","orderType":"LIMIT","expireDate":"2026-10-30",
                  "first":{"type":"STOP","status":"WATCHING","triggerPrice":"80000","targetProfitRate":null,"orderPrice":"80000","triggeredOrderId":null},
                  "second":{"type":"STOP","status":"CANCELED","triggerPrice":"60000","targetProfitRate":null,"orderPrice":"60000","triggeredOrderId":null},
                  "createdAt":"2026-09-30T09:00:00+09:00"},
                 {"conditionalOrderId":"C-2","type":"SINGLE","status":"ORDERED","symbol":"005930","market":"KR","quantity":"3","orderType":"MARKET",
                  "first":{"type":"PROFIT_RATE","status":"ORDERED","triggerPrice":null,"targetProfitRate":"10.5","orderPrice":null,"triggeredOrderId":"ORD-7"},
                  "createdAt":"2026-09-30T09:05:00+09:00"}
               ],"nextCursor":"cur-2","hasNext":true}}""",
        )

        val page =
            adapter.conditionalOrders(
                user,
                ConditionalOrdersQuery(ConditionalOrderScope.OPEN, TradingFixtures.samsung, null),
            )

        server.verify(
            getRequestedFor(urlPathEqualTo("/api/v1/conditional-orders"))
                .withQueryParam("status", equalTo("OPEN"))
                .withQueryParam("symbol", equalTo("005930"))
                .withQueryParam("limit", equalTo("100"))
        )
        assertThat(page.nextCursor).isEqualTo("cur-2")
        val oco = page.conditionalOrders[0]
        assertThat(oco.type).isEqualTo(ConditionalOrderType.OCO)
        assertThat(oco.symbol).isEqualTo(Symbol(Market.KR, "005930"))
        assertThat(oco.expireDate).isEqualTo(LocalDate.of(2026, 10, 30))
        assertThat(oco.createdAt).isEqualTo(Instant.parse("2026-09-30T00:00:00Z"))
        assertThat(oco.second?.status).isEqualTo(ConditionLegStatus.CANCELED)
        assertThat(oco.first.triggerPrice).isEqualTo(Money.of("80000", Currency.KRW))
        val profitRate = page.conditionalOrders[1]
        assertThat(profitRate.status).isEqualTo(ConditionalOrderStatus.ORDERED)
        assertThat(profitRate.second).isNull()
        assertThat(profitRate.first.isPriceTrigger).isFalse()
        assertThat(profitRate.first.triggerPrice).isNull()
        assertThat(profitRate.first.triggeredOrderId).isEqualTo("ORD-7")
        assertThat(profitRate.quantity).isEqualTo(Quantity.of(3))
    }

    @Test
    @DisplayName("다음 쪽이 없으면 커서가 와도 버리고, 없는 조건주문 상세는 없는 값으로 올림")
    fun lastPageAndMissingDetail() {
        json(
            get(urlPathEqualTo("/api/v1/conditional-orders")),
            """{"result":{"conditionalOrders":[],"nextCursor":"stale","hasNext":false}}""",
        )
        error(
            get(urlPathEqualTo("/api/v1/conditional-orders/C-404")),
            404,
            "conditional-order-not-found",
        )

        val page =
            adapter.conditionalOrders(
                user,
                ConditionalOrdersQuery(ConditionalOrderScope.CLOSED, null, null),
            )

        assertThat(page.nextCursor).isNull()
        assertThatThrownBy { adapter.lookupConditionalOrder(user, "C-404") }
            .isInstanceOf(InvalidValueException::class.java)
    }

    private fun submission(intent: ConditionalOrderIntent, isHighValueConfirmed: Boolean = false) =
        ConditionalOrderSubmission(intent, ClientOrderId("KEY-1"), isHighValueConfirmed)

    private fun config(http: HttpProperties): TossHttpConfig {
        val shared = OkHttpConfig().okHttpClient(http)
        return TossHttpConfig(
            RetrofitFactory(shared),
            ExternalEndpointProperties(tossBaseUrl = "http://127.0.0.1:${server.port()}/"),
            shared,
        )
    }

    private fun adapterWith(config: TossHttpConfig) =
        TossConditionalOrderAdapter(
            config.tossOrderClient(tokens),
            TossAccountCache(TossAccountLookup(config.tossAccountClient(tokens))),
        )

    private fun json(request: MappingBuilder, body: String) {
        server.stubFor(
            request.willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)
            )
        )
    }

    private fun error(request: MappingBuilder, status: Int, code: String) {
        server.stubFor(
            request.willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"error":{"requestId":"r","code":"$code","message":"m"}}""")
            )
        )
    }
}
