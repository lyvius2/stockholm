package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialFields
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.shared.config.RetrofitFactory
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** 토스 키 검증은 토큰 발급과 계좌 목록 조회만 하고 주문 경로는 부르지 않음. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TossCredentialVerifierTest {
    private val server = WireMockServer(wireMockConfig().dynamicPort())
    private lateinit var verifier: TossCredentialVerifier
    private val clientId = "MARKER-TOSS-ID-11aa"
    private val clientSecret = "MARKER-TOSS-SECRET-22bb"

    @BeforeAll
    fun start() {
        server.start()
        val retrofit =
            RetrofitFactory(OkHttpClient()).create("http://127.0.0.1:${server.port()}/".toHttpUrl())
        verifier =
            TossCredentialVerifier(
                retrofit.create(TossAuthClient::class.java),
                retrofit.create(TossAccountProbeClient::class.java),
            )
    }

    @AfterAll fun stop() = server.stop()

    @BeforeEach fun reset() = server.resetAll()

    private fun stubToken(
        status: Int,
        body: String = """{"access_token":"tok-verify","token_type":"Bearer","expires_in":86400}""",
    ) {
        server.stubFor(
            post(urlPathEqualTo("/oauth2/token"))
                .withRequestBody(containing("client_id=$clientId"))
                .withRequestBody(containing("client_secret=$clientSecret"))
                .willReturn(
                    aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)
                )
        )
    }

    private fun stubAccounts(status: Int, body: String) {
        server.stubFor(
            get(urlPathEqualTo("/api/v1/accounts"))
                .withHeader("Authorization", equalTo("Bearer tok-verify"))
                .willReturn(
                    aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)
                )
        )
    }

    private fun verify() =
        verifier.verify(
            mapOf(
                CredentialFields.CLIENT_ID to SecretValue.of(clientId),
                CredentialFields.CLIENT_SECRET to SecretValue.of(clientSecret),
            )
        )

    @Test
    @DisplayName("토큰 발급 뒤 받은 토큰으로 계좌 목록을 읽고, 종합매매 계좌 순번과 개수를 detail 에 남김(계좌번호 없음)")
    fun issuesTokenThenReadsAccounts() {
        stubToken(200)
        stubAccounts(
            200,
            """{"result":[{"accountNo":"12345678901","accountSeq":7,"accountType":"BROKERAGE"},{"accountNo":"9","accountSeq":8,"accountType":"PENSION_SAVINGS"}]}""",
        )

        val check = verify() as CredentialCheck.Ok

        assertThat(check.detail)
            .containsEntry("accountCount", "1")
            .containsEntry("accountSeq", "7")
            .containsEntry("accountSeqs", "7")
        assertThat(check.detail.values).noneMatch { it.contains("12345678901") }
        server.verify(0, postRequestedFor(urlPathMatching("/api/v1/orders.*")))
    }

    @Test
    @DisplayName("토큰 발급이 400·401·403 이면 키 거부, 429 는 일시 실패, 5xx 는 닿지 못함")
    fun tokenFailures() {
        stubToken(400, """{"error":"invalid_client"}""")
        assertThat((verify() as CredentialCheck.Rejected).reason).contains("키")
        stubToken(401, "{}")
        assertThat(verify()).isInstanceOf(CredentialCheck.Rejected::class.java)
        stubToken(429, "{}")
        assertThat((verify() as CredentialCheck.Unreachable).reason).contains("한도")
        stubToken(503, "{}")
        assertThat(verify()).isInstanceOf(CredentialCheck.Unreachable::class.java)
    }

    @Test
    @DisplayName("종합매매 계좌가 없으면 거부, 계좌 조회 5xx 와 결과 없는 200 은 닿지 못함")
    fun accountFailures() {
        stubToken(200)
        stubAccounts(200, """{"result":[{"accountSeq":8,"accountType":"PENSION_SAVINGS"}]}""")
        assertThat((verify() as CredentialCheck.Rejected).reason).contains("종합매매")

        stubAccounts(500, "{}")
        assertThat(verify()).isInstanceOf(CredentialCheck.Unreachable::class.java)

        // 결과 필드가 없는 200 은 빈 계좌 목록과 달리 키 거부가 아님
        stubAccounts(200, "{}")
        assertThat((verify() as CredentialCheck.Unreachable).reason).contains("결과가 없음")
    }

    @Test
    @DisplayName("fallback 사유에는 키가 없음")
    fun fallbackHidesSecrets() {
        val check =
            verifier.unreachable(
                mapOf(CredentialFields.CLIENT_ID to SecretValue.of(clientId)),
                RuntimeException("client_id=$clientId&client_secret=$clientSecret"),
            )
        val reason = (check as CredentialCheck.Unreachable).reason
        assertThat(reason).doesNotContain(clientId).doesNotContain(clientSecret)
        server.verify(0, postRequestedFor(anyUrl()))
    }
}
