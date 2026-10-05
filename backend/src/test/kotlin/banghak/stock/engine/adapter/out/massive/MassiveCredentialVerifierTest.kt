package banghak.stock.engine.adapter.out.massive

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
class MassiveCredentialVerifierTest {
    private val stub = VerifierStub()
    private lateinit var verifier: MassiveCredentialVerifier
    private val marker = "MARKER-MASSIVE-KEY-33cc"

    @BeforeAll
    fun start() {
        stub.start()
        verifier = MassiveCredentialVerifier(stub.client())
    }

    @AfterAll fun stop() = stub.stop()

    @BeforeEach fun reset() = stub.reset()

    private fun respond(status: Int, body: String = "{}") =
        stub.respond(
            get(urlPathEqualTo("/v3/reference/tickers/AAPL"))
                .withHeader("Authorization", equalTo("Bearer $marker")),
            status,
            body,
        )

    private fun verify() = verifier.verify(mapOf("VALUE" to SecretValue.of(marker)))

    @Test
    @DisplayName("AAPL 개요가 200 에 status=OK 면 검증됨, 빈 200 은 일시 실패, 403 은 거부, 429 는 한도라 일시 실패")
    fun statusMapping() {
        respond(200, """{"status":"OK","results":{"ticker":"AAPL"}}""")
        assertThat((verify() as CredentialCheck.Ok).detail).containsEntry("status", "OK")
        respond(403)
        assertThat(verify()).isInstanceOf(CredentialCheck.Rejected::class.java)
        respond(200, "{}")
        assertThat((verify() as CredentialCheck.Unreachable).reason).contains("상태")
        respond(200, """{"status":"ERROR"}""")
        assertThat(verify()).isInstanceOf(CredentialCheck.Unreachable::class.java)
        respond(429)
        assertThat((verify() as CredentialCheck.Unreachable).reason).contains("한도")
        assertThat(stub.server.allServeEvents).allMatch { !it.request.url.contains(marker) }
    }

    @Test
    @DisplayName("fallback 사유에는 키가 없음")
    fun fallbackHidesKey() {
        val check =
            verifier.unreachable(mapOf("VALUE" to SecretValue.of(marker)), RuntimeException(marker))
        assertThat((check as CredentialCheck.Unreachable).reason).doesNotContain(marker)
    }
}
