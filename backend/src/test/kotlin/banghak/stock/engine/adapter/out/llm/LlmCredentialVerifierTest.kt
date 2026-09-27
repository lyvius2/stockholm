package banghak.stock.engine.adapter.out.llm

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.shared.config.RetrofitFactory
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** WireMock 으로 200/401/429/연결 실패를 흉내 내어 `CredentialCheck` 매핑을 확인함. 키가 헤더에만 실리는지도 봄. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LlmCredentialVerifierTest {
    private val server = WireMockServer(wireMockConfig().dynamicPort())
    private lateinit var retrofit: RetrofitFactory
    private val marker = "MARKER-LLM-KEY-31c9"

    @BeforeAll
    fun start() {
        server.start()
        retrofit = RetrofitFactory(OkHttpClient())
    }

    @AfterAll fun stop() = server.stop()

    private fun <T> client(type: Class<T>): T =
        retrofit.create("http://127.0.0.1:${server.port()}/".toHttpUrl()).create(type)

    private fun fields() = mapOf("VALUE" to SecretValue.of(marker))

    @Test
    @DisplayName("OpenAI: 200 이면 모델 수와 함께 Ok, 401 은 Rejected, 429 는 한도 초과")
    fun openAiMapping() {
        val verifier = OpenAiCredentialVerifier(client(OpenAiModelsClient::class.java))
        server.stubFor(
            get(urlEqualTo("/v1/models"))
                .withHeader("Authorization", equalTo("Bearer $marker"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"data":[{"id":"a"},{"id":"b"}]}""")
                )
        )
        assertThat(verifier.verify(fields()))
            .isEqualTo(CredentialCheck.Ok(mapOf("modelCount" to "2")))
        server.stubFor(get(urlEqualTo("/v1/models")).willReturn(aResponse().withStatus(401)))
        assertThat(verifier.verify(fields())).isInstanceOf(CredentialCheck.Rejected::class.java)
        server.stubFor(get(urlEqualTo("/v1/models")).willReturn(aResponse().withStatus(429)))
        assertThat((verifier.verify(fields()) as CredentialCheck.Rejected).reason).contains("한도")
    }

    @Test
    @DisplayName("Claude: x-api-key 와 anthropic-version 헤더로 부르고 200 이면 Ok")
    fun anthropicMapping() {
        val verifier = AnthropicCredentialVerifier(client(AnthropicModelsClient::class.java))
        server.stubFor(
            get(urlEqualTo("/v1/models"))
                .withHeader("x-api-key", equalTo(marker))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"data":[{"id":"m"}]}""")
                )
        )
        assertThat(verifier.verify(fields()).isOk).isTrue()
    }

    @Test
    @DisplayName("DeepSeek: /models 를 Bearer 로 부르고 403 은 Rejected")
    fun deepSeekMapping() {
        val verifier = DeepSeekCredentialVerifier(client(DeepSeekModelsClient::class.java))
        server.stubFor(
            get(urlEqualTo("/models"))
                .withHeader("Authorization", equalTo("Bearer $marker"))
                .willReturn(aResponse().withStatus(403))
        )
        assertThat(verifier.verify(fields())).isInstanceOf(CredentialCheck.Rejected::class.java)
    }

    @Test
    @DisplayName("Ollama: 주소의 /api/tags 가 200 이면 Ok 이고 모델이 없으면 경고, 주소 형식이 틀리면 Rejected")
    fun ollamaMapping() {
        val verifier = OllamaCredentialVerifier(client(OllamaTagsClient::class.java))
        server.stubFor(
            get(urlEqualTo("/api/tags"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"models":[{"name":"bge-m3:latest"}]}""")
                )
        )
        val ok =
            verifier.verify(mapOf("VALUE" to SecretValue.of("http://127.0.0.1:${server.port()}/")))
                as CredentialCheck.Ok
        assertThat(ok.detail["modelCount"]).isEqualTo("1")
        server.stubFor(
            get(urlEqualTo("/api/tags"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"models":[]}""")
                )
        )
        val empty =
            verifier.verify(mapOf("VALUE" to SecretValue.of("http://127.0.0.1:${server.port()}")))
                as CredentialCheck.Ok
        assertThat(empty.detail["warning"]).isEqualTo("설치된 모델 없음")
        assertThat(verifier.verify(mapOf("VALUE" to SecretValue.of("not a url"))))
            .isInstanceOf(CredentialCheck.Rejected::class.java)
    }

    @Test
    @DisplayName("연결 실패는 예외로 올라가 서킷 브레이커가 세고, fallback 은 키가 든 메시지를 남기지 않음")
    fun connectionFailureGoesToFallback() {
        val dead =
            retrofit
                .create("http://127.0.0.1:1/".toHttpUrl())
                .create(OpenAiModelsClient::class.java)
        val verifier = OpenAiCredentialVerifier(dead)
        assertThatThrownBy { verifier.verify(fields()) }.isInstanceOf(IOException::class.java)
        val fallback =
            verifier.unreachable(fields(), IOException("GET https://x/?key=$marker failed"))
        assertThat(fallback).isInstanceOf(CredentialCheck.Unreachable::class.java)
        assertThat((fallback as CredentialCheck.Unreachable).reason)
            .doesNotContain(marker)
            .contains("IOException")
    }
}
