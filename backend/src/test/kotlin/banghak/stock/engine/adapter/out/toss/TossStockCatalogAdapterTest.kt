package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.KrTradingDetail
import banghak.stock.core.domain.market.ListingBoard
import banghak.stock.core.domain.market.ListingStatus
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.SecurityType
import banghak.stock.core.domain.market.StockWarning
import banghak.stock.core.domain.market.StockWarningType
import banghak.stock.core.domain.market.Symbol
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
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 토스 종목 정보 어댑터를 WireMock 과 학습 테스트 픽스처로 검증함.
 * 실제 토스로는 요청이 나가지 않음.
 */
class TossStockCatalogAdapterTest {
    private val server = WireMockServer(wireMockConfig().dynamicPort())
    private val clock = MutableClock(Instant.parse("2026-09-29T00:00:00Z"))
    private val admin = UserId.from(Ulid.of(clock.instant(), ByteArray(10)))
    private val secrets = MemorySecretStore()
    private val samsung = Symbol(Market.KR, "005930")
    private val nvidia = Symbol(Market.US, "NVDA")
    private lateinit var catalog: TossStockCatalogAdapter

    @BeforeEach
    fun setUp() {
        server.start()
        val http = OkHttpConfig().okHttpClient(HttpProperties())
        val config =
            TossHttpConfig(
                RetrofitFactory(http),
                ExternalEndpointProperties(tossBaseUrl = "http://127.0.0.1:${server.port()}/"),
                http,
            )
        val tokens = TossTokenCache(TossTokenIssuer(config.tossAuthClient(), secrets, clock), clock)
        catalog = TossStockCatalogAdapter(config.tossStockClient(tokens)) { TossCaller(admin) }
        secrets.put(
            SecretKey.user(admin, CredentialKind.TOSS.secretName("CLIENT_ID")),
            SecretValue.of("id"),
        )
        secrets.put(
            SecretKey.user(admin, CredentialKind.TOSS.secretName("CLIENT_SECRET")),
            SecretValue.of("secret"),
        )
        server.stubFor(
            post(urlPathEqualTo("/oauth2/token"))
                .willReturn(
                    json("""{"access_token":"tok-1","token_type":"Bearer","expires_in":86400}""")
                )
        )
    }

    @AfterEach fun tearDown() = server.stop()

    @Test
    @DisplayName("시장별 전체 종목은 코드만 쓰고, 코드 형식이 맞지 않는 신주인수권(8자리) 같은 행은 건너뜀")
    fun listedSymbolsSkipMalformedCodes() {
        stub(
            "/api/v1/stocks/all",
            """{"result":[
              {"symbol":"005930","name":"삼성전자","securityType":"STOCK","isCommonShare":true,"isinCode":"KR7005930003"},
              {"symbol":"2109801G","name":"신주인수권","securityType":"STOCK_WARRANTS","isCommonShare":false,"isinCode":"KR8"},
              {"symbol":"005935","name":"삼성전자우","securityType":"STOCK","isCommonShare":false,"isinCode":"KR7005931001"}]}""",
        )

        val symbols = catalog.listedSymbols(ListingBoard.KOSPI)

        assertThat(symbols).containsExactly(samsung, Symbol(Market.KR, "005935"))
        server.verify(
            getRequestedFor(urlPathEqualTo("/api/v1/stocks/all"))
                .withQueryParam("market", equalTo("KOSPI"))
                .withHeader("Authorization", equalTo("Bearer tok-1"))
        )
    }

    @Test
    @DisplayName("종목 정보는 쉼표로 묶어 한 번에 받고 상장 시장·상태·상장일·국내 거래 상태를 옮김")
    fun profilesFromFixture() {
        server.stubFor(get(urlPathEqualTo("/api/v1/stocks")).willReturn(json(fixture("stocks"))))

        val profiles = catalog.profiles(listOf(samsung, nvidia)).associateBy { it.symbol }

        val kr = profiles.getValue(samsung)
        assertThat(kr.board).isEqualTo(ListingBoard.KOSPI)
        assertThat(kr.securityType).isEqualTo(SecurityType.STOCK)
        assertThat(kr.status).isEqualTo(ListingStatus.ACTIVE)
        assertThat(kr.listedOn).isEqualTo(LocalDate.of(1975, 6, 11))
        assertThat(kr.krDetail).isEqualTo(KrTradingDetail(false, true, false, false))
        val us = profiles.getValue(nvidia)
        assertThat(us.board).isEqualTo(ListingBoard.NASDAQ)
        assertThat(us.krDetail).isNull()
        server.verify(
            getRequestedFor(urlPathEqualTo("/api/v1/stocks"))
                .withQueryParam("symbols", equalTo("005930,NVDA"))
        )
    }

    @Test
    @DisplayName("요청과 시장·통화가 다르거나 국내 거래정지 값이 빠진 행은 저장되지 않게 버림")
    fun profilesDropRowsThatCannotBeTrusted() {
        stub(
            "/api/v1/stocks",
            """{"result":[
              ${stockRow("005930", "KOSPI", "USD", """{"liquidationTrading":false,"nxtSupported":true,"krxTradingSuspended":false}""")},
              ${stockRow("000660", "KOSPI", "KRW", """{"liquidationTrading":false,"nxtSupported":true}""")},
              ${stockRow("035720", "NASDAQ", "KRW", "null")},
              ${stockRow("005380", "KOSPI", "KRW", """{"liquidationTrading":false,"nxtSupported":true,"krxTradingSuspended":true,"nxtTradingSuspended":null}""")}]}""",
        )
        val requested = listOf("005930", "000660", "035720", "005380").map { Symbol(Market.KR, it) }

        val profiles = catalog.profiles(requested)

        assertThat(profiles.map { it.symbol.code }).containsExactly("005380")
        assertThat(profiles.single().krDetail?.isKrxSuspended).isTrue()
    }

    @Test
    @DisplayName("종목 정보는 한 번에 200개까지이며 넘으면 부르지 않고 거부함")
    fun rejectsMoreThanTwoHundredSymbols() {
        val tooMany = (0..200).map { Symbol(Market.KR, "%06d".format(it)) }

        assertThatThrownBy { catalog.profiles(tooMany) }
            .isInstanceOf(InvalidValueException::class.java)
        server.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/stocks")))
    }

    @Test
    @DisplayName("매수 유의사항은 종류·기간을 옮기고 모르는 종류는 UNKNOWN 으로 둠")
    fun mapsWarnings() {
        stub(
            "/api/v1/stocks/005930/warnings",
            """{"result":[
              {"warningType":"VI_STATIC","exchange":"KRX","startDate":"2026-09-29","endDate":"2026-09-29"},
              {"warningType":"SOMETHING_NEW","exchange":null,"startDate":null,"endDate":null}]}""",
        )

        val warnings = catalog.warnings(samsung)

        assertThat(warnings)
            .containsExactly(
                StockWarning(
                    StockWarningType.VI_STATIC,
                    LocalDate.of(2026, 9, 29),
                    LocalDate.of(2026, 9, 29),
                ),
                StockWarning(StockWarningType.UNKNOWN, null, null),
            )
    }

    @Test
    @DisplayName("없는 종목의 유의사항 조회는 잘못된 요청으로 봄")
    fun unknownSymbolWarningsAreInvalid() {
        server.stubFor(
            get(urlPathEqualTo("/api/v1/stocks/999999/warnings"))
                .willReturn(
                    aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"error":{"requestId":"r","code":"stock-not-found","message":"m"}}"""
                        )
                )
        )

        assertThatThrownBy { catalog.warnings(Symbol(Market.KR, "999999")) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    private fun stockRow(code: String, market: String, currency: String, detail: String) =
        """{"symbol":"$code","name":"종목$code","englishName":"S$code","isinCode":"KR7${code}000","market":"$market","securityType":"STOCK","isCommonShare":true,"status":"ACTIVE","currency":"$currency","listDate":"2000-01-04","delistDate":null,"sharesOutstanding":"1000","leverageFactor":null,"koreanMarketDetail":$detail}"""

    private fun stub(path: String, body: String) {
        server.stubFor(get(urlPathEqualTo(path)).willReturn(json(body)))
    }

    private fun json(body: String) =
        aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/wiremock/toss/$name.json")) { "픽스처 없음: $name" }
            .readText()
}
