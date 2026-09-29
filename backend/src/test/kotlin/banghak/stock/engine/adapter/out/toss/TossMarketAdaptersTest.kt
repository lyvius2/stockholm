package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.error.BrokerAccessDeniedException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.error.SecretMissingException
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.MarketSession
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.CandleInterval
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.engine.adapter.out.keychain.SecretReader
import banghak.stock.engine.config.ExternalEndpointProperties
import banghak.stock.engine.config.TossHttpConfig
import banghak.stock.shared.config.HttpProperties
import banghak.stock.shared.config.OkHttpConfig
import banghak.stock.shared.config.RetrofitFactory
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.MemorySecretStore
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.stubbing.Scenario
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 토스 시세·달력 어댑터를 WireMock 과 학습 테스트 픽스처로 검증함.
 * 실제 토스로는 요청이 나가지 않음.
 */
class TossMarketAdaptersTest {
    private val server = WireMockServer(wireMockConfig().dynamicPort())
    private val user = UserId.from(Ulid.of(Instant.parse("2026-09-28T00:00:00Z"), ByteArray(10)))
    private val clock = MutableClock(Instant.parse("2026-09-28T00:00:00Z"))
    private val secrets = MemorySecretStore()
    private val samsung = Symbol(Market.KR, "005930")
    private val hynix = Symbol(Market.KR, "000660")
    private val nvidia = Symbol(Market.US, "NVDA")
    private lateinit var marketData: TossMarketDataAdapter
    private lateinit var calendar: TossMarketCalendarAdapter
    private lateinit var config: TossHttpConfig

    @BeforeEach
    fun setUp() {
        server.start()
        val retrofit = RetrofitFactory(OkHttpConfig().okHttpClient(HttpProperties()))
        config =
            TossHttpConfig(
                retrofit,
                ExternalEndpointProperties(tossBaseUrl = "http://127.0.0.1:${server.port()}/"),
                OkHttpConfig().okHttpClient(HttpProperties()),
            )
        val tokens = TossTokenCache(TossTokenIssuer(config.tossAuthClient(), secrets, clock), clock)
        val callers = TossCallerResolver { TossCaller(user) }
        marketData =
            TossMarketDataAdapter(
                config.tossPriceClient(tokens),
                config.tossChartClient(tokens),
                config.tossMarketInfoClient(tokens),
                callers,
            )
        calendar = TossMarketCalendarAdapter(config.tossMarketInfoClient(tokens), callers)
        secrets.put(
            SecretKey.user(user, CredentialKind.TOSS.secretName("CLIENT_ID")),
            SecretValue.of("client-id-1"),
        )
        secrets.put(
            SecretKey.user(user, CredentialKind.TOSS.secretName("CLIENT_SECRET")),
            SecretValue.of("secret-1"),
        )
        stubToken("tok-1")
    }

    @AfterEach fun tearDown() = server.stop()

    @Test
    @DisplayName("토큰은 한 번 발급해 재사용하고 Bearer 헤더로 붙이며, 발급 요청은 폼으로 client_credentials 를 보냄")
    fun issuesTokenOnceAndSendsBearer() {
        stubResult("/api/v1/prices", "prices")

        marketData.quotes(listOf(samsung))
        marketData.quotes(listOf(samsung))

        server.verify(
            1,
            postRequestedFor(urlPathEqualTo("/oauth2/token"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(containing("client_id=client-id-1")),
        )
        server.verify(
            2,
            getRequestedFor(urlPathEqualTo("/api/v1/prices"))
                .withHeader("Authorization", equalTo("Bearer tok-1")),
        )
    }

    @Test
    @DisplayName("401 을 받으면 토큰을 한 번 갱신해 다시 보냄")
    fun renewsTokenOnceAfter401() {
        server.stubFor(
            post(urlPathEqualTo("/oauth2/token"))
                .inScenario("token")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(tokenBody("tok-1"))
                .willSetStateTo("renewed")
        )
        server.stubFor(
            post(urlPathEqualTo("/oauth2/token"))
                .inScenario("token")
                .whenScenarioStateIs("renewed")
                .willReturn(tokenBody("tok-2"))
        )
        server.stubFor(
            get(urlPathEqualTo("/api/v1/prices"))
                .withHeader("Authorization", equalTo("Bearer tok-1"))
                .willReturn(aResponse().withStatus(401))
        )
        server.stubFor(
            get(urlPathEqualTo("/api/v1/prices"))
                .withHeader("Authorization", equalTo("Bearer tok-2"))
                .willReturn(fixture("prices"))
        )

        assertThat(marketData.quotes(listOf(samsung))).hasSize(1)
        server.verify(2, postRequestedFor(urlPathEqualTo("/oauth2/token")))
    }

    @Test
    @DisplayName("현재가는 요청한 종목만 시장 통화 금액과 체결 시각으로 돌려줌")
    fun mapsQuotes() {
        stubResult("/api/v1/prices", "prices")

        val quotes = marketData.quotes(listOf(samsung, hynix, nvidia)).associateBy { it.symbol }

        assertThat(quotes.getValue(samsung).last).isEqualTo(Money.of("286500", Currency.KRW))
        assertThat(quotes.getValue(samsung).asOf).isEqualTo(kst("2026-09-23T19:59:59"))
        assertThat(quotes.getValue(nvidia).last).isEqualTo(Money.of("225.00", Currency.USD))
        assertThat(marketData.quotes(listOf(samsung)).map { it.symbol }).containsExactly(samsung)
        assertThatThrownBy { marketData.quotes(List(201) { samsung }) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("1분봉 시각은 봉 종료 시각이라 1분 앞당겨 openTime 으로, 일봉은 그날 00:00 그대로 둠")
    fun mapsCandles() {
        stubResult("/api/v1/candles", "candles-005930-1m", interval = "1m")
        stubResult("/api/v1/candles", "candles-005930-1d", interval = "1d")

        val minute = marketData.candlePage(samsung, CandleInterval.MINUTE_1, null, 3)
        val day =
            marketData.candlePage(samsung, CandleInterval.DAY_1, kst("2026-09-24T00:00:00"), 5)

        assertThat(minute.candles.first().openTime).isEqualTo(kst("2026-09-23T19:59:00"))
        assertThat(minute.candles.first().volume).isEqualTo(Quantity.of(97106))
        assertThat(minute.nextBefore).isEqualTo(kst("2026-09-23T19:57:00"))
        assertThat(day.candles.first().openTime).isEqualTo(kst("2026-09-23T00:00:00"))
        assertThat(day.candles.first().close).isEqualTo(Money.of("286500", Currency.KRW))
        server.verify(
            getRequestedFor(urlPathEqualTo("/api/v1/candles"))
                .withQueryParam("before", equalTo("2026-09-24T00:00:00+09:00"))
        )
    }

    @Test
    @DisplayName("환율은 validFrom 을 기준 시각으로 둠")
    fun mapsExchangeRate() {
        stubResult("/api/v1/exchange-rate", "exchange-rate-usdkrw")

        val rate = marketData.exchangeRate(Currency.USD, Currency.KRW)

        assertThat(rate.rate).isEqualByComparingTo(BigDecimal("1362.6"))
        assertThat(rate.asOf).isEqualTo(kst("2026-09-27T23:57:36"))
    }

    @Test
    @DisplayName("과거 시점 환율은 dateTime 을 KST 오프셋의 ISO-8601 로 보내고, 현재 환율은 보내지 않음")
    fun sendsDateTimeForPastRate() {
        stubResult("/api/v1/exchange-rate", "exchange-rate-usdkrw")

        marketData.exchangeRateAt(Currency.USD, Currency.KRW, Instant.parse("2026-09-29T14:30:00Z"))
        marketData.exchangeRate(Currency.USD, Currency.KRW)

        server.verify(
            1,
            getRequestedFor(urlPathEqualTo("/api/v1/exchange-rate"))
                .withQueryParam("dateTime", equalTo("2026-09-29T23:30:00+09:00")),
        )
        server.verify(
            1,
            getRequestedFor(urlPathEqualTo("/api/v1/exchange-rate"))
                .withQueryParam("dateTime", absent()),
        )
    }

    @Test
    @DisplayName("휴장일은 빈 세션, 거래일은 국내 동시호가·미국 네 세션을 KST 그대로 담음")
    fun mapsCalendars() {
        stubResult("/api/v1/market-calendar/KR", "market-calendar-kr")
        assertThat(calendar.tradingDay(Market.KR, LocalDate.of(2026, 9, 27)).isHoliday).isTrue()

        stubBody("/api/v1/market-calendar/KR", nextBusinessDayAsToday("market-calendar-kr"))
        val kr = calendar.tradingDay(Market.KR, LocalDate.of(2026, 9, 28))
        assertThat(kr.window(MarketSession.REGULAR)?.auctionStart)
            .isEqualTo(kst("2026-09-28T15:20:00"))
        assertThat(kr.window(MarketSession.AFTER)?.auctionEnd).isEqualTo(kst("2026-09-28T15:40:00"))
        assertThat(kr.sessionAt(kst("2026-09-28T10:00:00"))).isEqualTo(MarketSession.REGULAR)

        stubBody("/api/v1/market-calendar/US", nextBusinessDayAsToday("market-calendar-us"))
        val us = calendar.tradingDay(Market.US, LocalDate.of(2026, 9, 28))
        assertThat(us.sessions.map { it.session })
            .containsExactly(
                MarketSession.DAY_MARKET,
                MarketSession.PRE,
                MarketSession.REGULAR,
                MarketSession.AFTER,
            )
        assertThat(us.window(MarketSession.AFTER)?.end).isEqualTo(kst("2026-09-29T08:50:00"))
    }

    @Test
    @DisplayName("403 은 접속 거부, 404 는 없는 대상, 429·5xx 는 시세 없음으로 바꾸고 원문은 싣지 않음")
    fun mapsErrors() {
        stubError(403, "ip-not-allowed")
        assertThatThrownBy { marketData.quotes(listOf(samsung)) }
            .isInstanceOf(BrokerAccessDeniedException::class.java)
            .hasMessageContaining("ip-not-allowed")
        stubError(404, "stock-not-found")
        assertThatThrownBy { marketData.quotes(listOf(samsung)) }
            .isInstanceOf(InvalidValueException::class.java)
        stubError(429, "rate-limit-exceeded")
        assertThatThrownBy { marketData.quotes(listOf(samsung)) }
            .isInstanceOf(MarketDataUnavailableException::class.java)
        assertThatThrownBy {
                marketData.quotesUnavailable(listOf(samsung), IOException("GET http://x?secret=1"))
            }
            .isInstanceOf(MarketDataUnavailableException::class.java)
            .hasMessageNotContaining("secret")
    }

    @Test
    @DisplayName("토스 키가 없으면 발급을 시도하지 않고 등록 안내 예외를 냄")
    fun missingKeyRaisesSecretMissing() {
        secrets.delete(SecretKey.user(user, CredentialKind.TOSS.secretName("CLIENT_ID")))
        stubResult("/api/v1/prices", "prices")

        assertThatThrownBy { marketData.quotes(listOf(samsung)) }
            .isInstanceOf(SecretMissingException::class.java)
        server.verify(0, postRequestedFor(urlPathEqualTo("/oauth2/token")))
    }

    @Test
    @DisplayName("응답 통화가 종목 통화와 다르거나 환율 통화쌍이 요청과 다르면 조회 실패로 봄")
    fun rejectsUnexpectedCurrencies() {
        stubBody(
            "/api/v1/prices",
            """{"result":[{"symbol":"005930","timestamp":"2026-09-23T19:59:59.000+09:00","lastPrice":"100","currency":"USD"}]}""",
        )
        assertThatThrownBy { marketData.quotes(listOf(samsung)) }
            .isInstanceOf(MarketDataUnavailableException::class.java)

        stubBody(
            "/api/v1/candles",
            """{"result":{"candles":[{"timestamp":"2026-09-23T00:00:00.000+09:00","openPrice":"1","highPrice":"1","lowPrice":"1","closePrice":"1","volume":"1","currency":"USD"}],"nextBefore":null}}""",
        )
        assertThatThrownBy { marketData.candlePage(samsung, CandleInterval.DAY_1, null, 1) }
            .isInstanceOf(MarketDataUnavailableException::class.java)

        stubBody(
            "/api/v1/exchange-rate",
            """{"result":{"baseCurrency":"KRW","quoteCurrency":"USD","rate":"0.00073","validFrom":"2026-09-27T23:57:36.000+09:00","validUntil":"2026-09-28T00:02:34.000+09:00"}}""",
        )
        assertThatThrownBy { marketData.exchangeRate(Currency.USD, Currency.KRW) }
            .isInstanceOf(MarketDataUnavailableException::class.java)
    }

    @Test
    @DisplayName("요청한 날짜와 다른 날의 달력이 오면 조회 실패로 봄")
    fun rejectsCalendarOfAnotherDate() {
        stubResult("/api/v1/market-calendar/KR", "market-calendar-kr")

        assertThatThrownBy { calendar.tradingDay(Market.KR, LocalDate.of(2026, 9, 28)) }
            .isInstanceOf(MarketDataUnavailableException::class.java)
    }

    @Test
    @DisplayName("토큰을 새로 받아도 401 이면 한 번만 재시도하고 인증 실패(접속 거부)로 봄")
    fun repeated401IsAuthenticationFailure() {
        server.stubFor(
            get(urlPathEqualTo("/api/v1/prices")).willReturn(aResponse().withStatus(401))
        )

        assertThatThrownBy { marketData.quotes(listOf(samsung)) }
            .isInstanceOf(BrokerAccessDeniedException::class.java)
        server.verify(2, postRequestedFor(urlPathEqualTo("/oauth2/token")))
        server.verify(2, getRequestedFor(urlPathEqualTo("/api/v1/prices")))
    }

    @Test
    @DisplayName("두 번째 비밀값이 없어도 먼저 읽은 비밀값은 지움")
    fun wipesFirstSecretWhenSecondIsMissing() {
        secrets.delete(SecretKey.user(user, CredentialKind.TOSS.secretName("CLIENT_SECRET")))
        val handedOut = mutableListOf<SecretValue>()
        val recording =
            object : SecretReader {
                override fun read(key: SecretKey): SecretValue? =
                    secrets.read(key)?.also { handedOut += it }
            }
        val issuer = TossTokenIssuer(config.tossAuthClient(), recording, clock)

        assertThatThrownBy { issuer.issue(TossCaller(user)) }
            .isInstanceOf(SecretMissingException::class.java)
        assertThat(handedOut).hasSize(1)
        assertThat(handedOut.single().reveal()).containsOnly(Char.MIN_VALUE)
    }

    @Test
    @DisplayName("호가는 매도 낮은 가격순·매수 높은 가격순 전체 스냅샷을 옮기고 시각을 가짐")
    fun mapsOrderBook() {
        stubResult("/api/v1/orderbook", "orderbook-005930")

        val book = marketData.orderBook(samsung)

        assertThat(book.asks).isNotEmpty()
        assertThat(book.asks.map { it.price.amount }).isSorted()
        assertThat(book.bids.map { it.price.amount }).isSortedAccordingTo(reverseOrder())
        assertThat(book.asks.first().price.currency).isEqualTo(Currency.KRW)
        assertThat(book.asOf).isEqualTo(kst("2026-09-29T16:33:28"))
    }

    @Test
    @DisplayName("호가 통화가 종목 통화와 다르면 조회 실패로 봄")
    fun rejectsOrderBookInOtherCurrency() {
        stubBody(
            "/api/v1/orderbook",
            """{"result":{"timestamp":null,"currency":"USD","asks":[],"bids":[]}}""",
        )

        assertThatThrownBy { marketData.orderBook(samsung) }
            .isInstanceOf(MarketDataUnavailableException::class.java)
    }

    private fun stubToken(token: String) {
        server.stubFor(post(urlPathEqualTo("/oauth2/token")).willReturn(tokenBody(token)))
    }

    private fun tokenBody(token: String) =
        aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("""{"access_token":"$token","token_type":"Bearer","expires_in":86400}""")

    private fun stubResult(path: String, fixtureName: String, interval: String? = null) {
        val request =
            get(urlPathEqualTo(path)).let {
                if (interval != null) it.withQueryParam("interval", equalTo(interval)) else it
            }
        server.stubFor(request.willReturn(fixture(fixtureName)))
    }

    private fun stubBody(path: String, body: String) {
        server.stubFor(
            get(urlPathEqualTo(path))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)
                )
        )
    }

    private fun stubError(status: Int, code: String) {
        server.stubFor(
            get(urlPathEqualTo("/api/v1/prices"))
                .willReturn(
                    aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"error":{"requestId":"r","code":"$code","message":"m"}}""")
                )
        )
    }

    private fun fixture(name: String) =
        aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(fixtureText(name))

    private fun fixtureText(name: String): String =
        requireNotNull(javaClass.getResource("/wiremock/toss/$name.json")) { "픽스처 없음: $name" }
            .readText()

    private fun nextBusinessDayAsToday(name: String): String {
        val mapper = ObjectMapper()
        val root = mapper.readTree(fixtureText(name))
        val result = root.get("result") as ObjectNode
        result.set<ObjectNode>("today", result.get("nextBusinessDay"))
        return mapper.writeValueAsString(root)
    }

    private fun kst(local: String): Instant = OffsetDateTime.parse("$local+09:00").toInstant()
}
