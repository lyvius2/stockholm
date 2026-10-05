package banghak.stock.engine.adapter.out.odcloud

import banghak.stock.core.domain.account.CredentialCheck
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
class OdcloudCredentialVerifierTest {
    private val stub = VerifierStub()
    private lateinit var verifier: OdcloudCredentialVerifier
    private val marker = "MARKER-ODCLOUD-KEY-66ff"

    @BeforeAll
    fun start() {
        stub.start()
        verifier = OdcloudCredentialVerifier(stub.client())
    }

    @AfterAll fun stop() = stub.stop()

    @BeforeEach fun reset() = stub.reset()

    private fun respond(status: Int, body: String = "{}") =
        stub.respond(
            get(
                    urlPathEqualTo(
                        "/api/3070517/v1/uddi:b9470910-bcb7-4048-82b6-d21d561594ea_201812140934"
                    )
                )
                .withQueryParam("serviceKey", equalTo(marker))
                .withQueryParam("perPage", equalTo("1")),
            status,
            body,
        )

    private fun verify() = verifier.verify(mapOf("VALUE" to SecretValue.of(marker)))

    @Test
    @DisplayName("2017년 말 데이터셋 1행이 200 이면 검증됨(총 건수 detail), 401 은 거부, 5xx 는 닿지 못함")
    fun statusMapping() {
        respond(200, """{"currentCount":1,"totalCount":312,"data":[{}]}""")
        assertThat((verify() as CredentialCheck.Ok).detail).containsEntry("totalCount", "312")
        respond(200, "{}")
        assertThat(verify()).isInstanceOf(CredentialCheck.Unreachable::class.java)
        respond(200, """{"currentCount":0,"totalCount":0,"data":[]}""")
        assertThat((verify() as CredentialCheck.Unreachable).reason).contains("행이 없음")
        respond(401, """{"code":-4,"msg":"Unauthorized"}""")
        assertThat(verify()).isInstanceOf(CredentialCheck.Rejected::class.java)
        respond(502)
        assertThat(verify()).isInstanceOf(CredentialCheck.Unreachable::class.java)
    }

    @Test
    @DisplayName("fallback 사유에는 키가 든 URL 이 없음")
    fun fallbackHidesUrl() {
        val check =
            verifier.unreachable(
                mapOf("VALUE" to SecretValue.of(marker)),
                RuntimeException("http://x/api/3070517/v1/uddi?serviceKey=$marker"),
            )
        assertThat((check as CredentialCheck.Unreachable).reason).doesNotContain(marker)
    }
}
