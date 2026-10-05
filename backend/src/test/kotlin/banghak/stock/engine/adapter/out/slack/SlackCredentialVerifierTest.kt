package banghak.stock.engine.adapter.out.slack

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.support.VerifierStub
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlackCredentialVerifierTest {
    private val stub = VerifierStub()
    private lateinit var verifier: SlackCredentialVerifier
    private val marker = "xoxb-MARKER-SLACK-77gg"

    @BeforeAll
    fun start() {
        stub.start()
        verifier = SlackCredentialVerifier(stub.client())
    }

    @AfterAll fun stop() = stub.stop()

    @BeforeEach fun reset() = stub.reset()

    private fun respond(status: Int, body: String = "{}") =
        stub.respond(
            post(urlPathEqualTo("/api/auth.test"))
                .withHeader("Authorization", equalTo("Bearer $marker")),
            status,
            body,
        )

    private fun verify() = verifier.verify(mapOf("VALUE" to SecretValue.of(marker)))

    @Test
    @DisplayName("auth.test 가 ok=true 면 워크스페이스 이름을 남기고, HTTP 200 이어도 ok=false 면 거부, 5xx 는 닿지 못함")
    fun okFlagDecides() {
        respond(
            200,
            """{"ok":true,"url":"https://x.slack.com/","team":"가족","user":"stockholm","bot_id":"B1"}""",
        )
        assertThat((verify() as CredentialCheck.Ok).detail).containsEntry("team", "가족")
        respond(200, """{"ok":false,"error":"invalid_auth"}""")
        assertThat((verify() as CredentialCheck.Rejected).reason).contains("invalid_auth")
        respond(503)
        assertThat(verify()).isInstanceOf(CredentialCheck.Unreachable::class.java)
    }

    @Test
    @DisplayName("fallback 사유에는 토큰이 없음")
    fun fallbackHidesToken() {
        val check = verifier.unreachable(emptyMap(), RuntimeException(marker))
        assertThat((check as CredentialCheck.Unreachable).reason).doesNotContain(marker)
    }
}
