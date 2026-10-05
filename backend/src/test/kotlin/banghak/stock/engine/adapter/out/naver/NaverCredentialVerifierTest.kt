package banghak.stock.engine.adapter.out.naver

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialFields
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.support.VerifierStub
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NaverCredentialVerifierTest {
    private val stub = VerifierStub()
    private lateinit var verifier: NaverCredentialVerifier
    private val clientId = "MARKER-NAVER-ID-44dd"
    private val clientSecret = "MARKER-NAVER-SECRET-55ee"

    @BeforeAll
    fun start() {
        stub.start()
        verifier = NaverCredentialVerifier(stub.client())
    }

    @AfterAll fun stop() = stub.stop()

    @BeforeEach fun reset() = stub.reset()

    private fun respond(status: Int, body: String = "{}") =
        stub.respond(
            get(urlPathEqualTo("/v1/search/news.json"))
                .withHeader("X-Naver-Client-Id", equalTo(clientId))
                .withHeader("X-Naver-Client-Secret", equalTo(clientSecret))
                .withQueryParam("query", equalTo(NaverCredentialVerifier.PROBE_QUERY))
                .withQueryParam("display", equalTo("1")),
            status,
            body,
        )

    private fun verify() =
        verifier.verify(
            mapOf(
                CredentialFields.CLIENT_ID to SecretValue.of(clientId),
                CredentialFields.CLIENT_SECRET to SecretValue.of(clientSecret),
            )
        )

    @Test
    @DisplayName("두 키를 헤더로 보내 뉴스 1건 검색이 200 이면 검증됨, 401 은 거부, 5xx 는 닿지 못함")
    fun statusMapping() {
        respond(200, """{"total":1,"items":[]}""")
        assertThat((verify() as CredentialCheck.Ok).detail).containsEntry("total", "1")
        respond(200, "{}")
        assertThat(verify()).isInstanceOf(CredentialCheck.Unreachable::class.java)
        respond(401, """{"errorMessage":"Authentication failed","errorCode":"024"}""")
        assertThat(verify()).isInstanceOf(CredentialCheck.Rejected::class.java)
        respond(500)
        assertThat(verify()).isInstanceOf(CredentialCheck.Unreachable::class.java)
    }

    @Test
    @DisplayName("fallback 사유에는 키가 없음")
    fun fallbackHidesKeys() {
        val check = verifier.unreachable(emptyMap(), RuntimeException("$clientId $clientSecret"))
        assertThat((check as CredentialCheck.Unreachable).reason)
            .doesNotContain(clientId)
            .doesNotContain(clientSecret)
    }
}
