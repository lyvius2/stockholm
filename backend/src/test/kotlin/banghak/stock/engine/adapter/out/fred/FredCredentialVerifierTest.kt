package banghak.stock.engine.adapter.out.fred

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
class FredCredentialVerifierTest {
    private val server = WireMockServer(wireMockConfig().dynamicPort())
    private lateinit var verifier: FredCredentialVerifier
    private val marker = "MARKER-FRED-KEY-a1b2"

    @BeforeAll
    fun start() {
        server.start()
        val client =
            RetrofitFactory(OkHttpClient())
                .create("http://127.0.0.1:${server.port()}/".toHttpUrl())
                .create(FredObservationsClient::class.java)
        verifier = FredCredentialVerifier(client)
    }

    @AfterAll fun stop() = server.stop()

    @Test
    @DisplayName("SP500 최근 1건 조회가 200 이면 Ok, 400 이면 키 거부")
    fun statusMapping() {
        server.stubFor(
            get(urlPathEqualTo("/fred/series/observations"))
                .withQueryParam("series_id", equalTo("SP500"))
                .withQueryParam("api_key", equalTo(marker))
                .withQueryParam("limit", equalTo("1"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"observations":[]}""")
                )
        )
        assertThat(verifier.verify(mapOf("VALUE" to SecretValue.of(marker))).isOk).isTrue()
        server.stubFor(
            get(urlPathEqualTo("/fred/series/observations"))
                .willReturn(
                    aResponse()
                        .withStatus(400)
                        .withBody(
                            """{"error_message":"Bad Request. The value for variable api_key is not registered."}"""
                        )
                )
        )
        assertThat(verifier.verify(mapOf("VALUE" to SecretValue.of(marker))))
            .isInstanceOf(CredentialCheck.Rejected::class.java)
    }
}
