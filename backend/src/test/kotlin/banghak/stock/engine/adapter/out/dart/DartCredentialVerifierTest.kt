package banghak.stock.engine.adapter.out.dart

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.shared.config.RetrofitFactory
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DartCredentialVerifierTest {
    private val server = WireMockServer(wireMockConfig().dynamicPort())
    private lateinit var verifier: DartCredentialVerifier
    private val marker = "MARKER-DART-KEY-0f7b"

    @BeforeAll
    fun start() {
        server.start()
        val client =
            RetrofitFactory(OkHttpClient())
                .create("http://127.0.0.1:${server.port()}/".toHttpUrl())
                .create(DartCompanyClient::class.java)
        verifier = DartCredentialVerifier(client)
    }

    @AfterAll fun stop() = server.stop()

    private fun stub(status: String) {
        server.stubFor(
            get(urlPathEqualTo("/api/company.json"))
                .withQueryParam("crtfc_key", equalTo(marker))
                .withQueryParam("corp_code", equalTo(DartCredentialVerifier.SAMSUNG_CORP_CODE))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"status":"$status","message":"x"}""")
                )
        )
    }

    private fun verify() = verifier.verify(mapOf("VALUE" to SecretValue.of(marker)))

    @Test
    @DisplayName("DART 상태 코드 000 → Ok, 010·011 → 키 거부, 020 한도 초과는 키 문제가 아니라 일시 실패, 800 → 점검 중")
    fun statusMapping() {
        stub("000")
        assertThat(verify().isOk).isTrue()
        stub("010")
        assertThat((verify() as CredentialCheck.Rejected).reason).contains("키")
        stub("020")
        assertThat((verify() as CredentialCheck.Unreachable).reason).contains("한도")
        stub("800")
        assertThat(verify()).isInstanceOf(CredentialCheck.Unreachable::class.java)
    }

    @Test
    @DisplayName("fallback 사유에는 키가 든 URL 이 없음")
    fun fallbackHidesUrl() {
        val check =
            verifier.unreachable(
                mapOf("VALUE" to SecretValue.of(marker)),
                RuntimeException("http://x/api/company.json?crtfc_key=$marker"),
            )
        assertThat((check as CredentialCheck.Unreachable).reason).doesNotContain(marker)
    }
}
