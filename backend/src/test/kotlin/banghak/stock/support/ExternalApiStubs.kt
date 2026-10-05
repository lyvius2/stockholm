package banghak.stock.support

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.springframework.test.context.DynamicPropertyRegistry

/**
 * 통합 테스트에서 외부 API(OpenAI·Claude·DeepSeek·DART·FRED·토스·Massive·네이버·공공데이터포털·Slack·KRX)를 WireMock 으로
 * 대신함.
 * 진짜 주소로 요청이 나가지 않게 base URL 을 전부 여기로 돌림.
 * 모두 "검증 성공" 응답.
 */
object ExternalApiStubs {
    private val server: WireMockServer by lazy {
        WireMockServer(wireMockConfig().dynamicPort()).also {
            it.start()
            val json = { body: String ->
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)
            }
            it.stubFor(
                get(urlPathEqualTo("/v1/models")).willReturn(json("""{"data":[{"id":"stub"}]}"""))
            )
            it.stubFor(
                get(urlPathEqualTo("/models")).willReturn(json("""{"data":[{"id":"stub"}]}"""))
            )
            it.stubFor(
                get(urlPathEqualTo("/api/company.json"))
                    .willReturn(json("""{"status":"000","message":"정상"}"""))
            )
            it.stubFor(
                get(urlPathEqualTo("/fred/series/observations"))
                    .willReturn(json("""{"observations":[]}"""))
            )
            it.stubFor(
                post(urlPathEqualTo("/oauth2/token"))
                    .willReturn(
                        json(
                            """{"access_token":"stub-token","token_type":"Bearer","expires_in":86400}"""
                        )
                    )
            )
            it.stubFor(
                get(urlPathEqualTo("/api/v1/accounts"))
                    .willReturn(json("""{"result":[{"accountSeq":1,"accountType":"BROKERAGE"}]}"""))
            )
            it.stubFor(
                get(urlPathMatching("/v3/reference/tickers/.*"))
                    .willReturn(json("""{"status":"OK","results":{}}"""))
            )
            it.stubFor(
                get(urlPathEqualTo("/v1/search/news.json"))
                    .willReturn(json("""{"total":1,"items":[]}"""))
            )
            it.stubFor(
                get(urlPathMatching("/api/3070517/v1/.*"))
                    .willReturn(json("""{"currentCount":1,"totalCount":100,"data":[]}"""))
            )
            it.stubFor(
                post(urlPathEqualTo("/api/auth.test"))
                    .willReturn(json("""{"ok":true,"team":"stub","user":"bot"}"""))
            )
            it.stubFor(
                post(urlPathEqualTo("/svc/apis/sto/stk_bydd_trd"))
                    .willReturn(json("""{"OutBlock_1":[{"ISU_CD":"005930"}]}"""))
            )
        }
    }

    fun register(registry: DynamicPropertyRegistry) {
        val base = { "http://127.0.0.1:${server.port()}/" }
        listOf(
                "openai",
                "anthropic",
                "deepseek",
                "dart",
                "fred",
                "toss",
                "massive",
                "naver",
                "odcloud",
                "slack",
                "krx",
            )
            .forEach {
                registry.add("stockholm.external.$it-base-url", base)
            }
        registry.add("stockholm.external.toss-web-socket-url") {
            "ws://127.0.0.1:${server.port()}/ws/v1"
        }
        registry.add("stockholm.kftc.base-url", base)
    }
}
