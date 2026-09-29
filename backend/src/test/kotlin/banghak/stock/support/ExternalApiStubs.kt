package banghak.stock.support

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.springframework.test.context.DynamicPropertyRegistry

/**
 * 통합 테스트에서 외부 API(OpenAI·Claude·DeepSeek·DART·FRED)를 WireMock 으로 대신함.
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
        }
    }

    fun register(registry: DynamicPropertyRegistry) {
        val base = { "http://127.0.0.1:${server.port()}/" }
        listOf("openai", "anthropic", "deepseek", "dart", "fred", "toss").forEach {
            registry.add("stockholm.external.$it-base-url", base)
        }
    }
}
