package banghak.stock.engine.adapter.out.krx

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.support.VerifierStub
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
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
class KrxCredentialVerifierTest {
    private val stub = VerifierStub()
    private lateinit var verifier: KrxCredentialVerifier
    private val marker = "MARKER-KRX-KEY-88hh"

    @BeforeAll
    fun start() {
        stub.start()
        verifier = KrxCredentialVerifier(stub.client())
    }

    @AfterAll fun stop() = stub.stop()

    @BeforeEach fun reset() = stub.reset()

    private fun respond(status: Int, body: String = "{}") =
        stub.respond(
            post(urlPathEqualTo("/svc/apis/sto/stk_bydd_trd"))
                .withHeader("AUTH_KEY", equalTo(marker))
                .withRequestBody(
                    equalToJson("""{"basDd":"${KrxCredentialVerifier.PROBE_TRADING_DAY}"}""")
                ),
            status,
            body,
        )

    private fun verify() = verifier.verify(mapOf("VALUE" to SecretValue.of(marker)))

    @Test
    @DisplayName("헤더 키와 JSON 기준일로 유가증권 일별매매를 받아 행이 있으면 검증됨, 빈 응답은 일시 실패, 401 은 거부")
    fun rowsDecide() {
        respond(
            200,
            """{"OutBlock_1":[{"BAS_DD":"20250102","ISU_CD":"005930","TDD_CLSPRC":"53,400"}]}""",
        )
        assertThat((verify() as CredentialCheck.Ok).detail).containsEntry("rowCount", "1")
        respond(200, """{"OutBlock_1":[]}""")
        assertThat((verify() as CredentialCheck.Unreachable).reason).contains("자료가 없음")
        respond(401)
        assertThat(verify()).isInstanceOf(CredentialCheck.Rejected::class.java)
    }

    @Test
    @DisplayName("fallback 사유에는 키가 없음")
    fun fallbackHidesKey() {
        val check = verifier.unreachable(emptyMap(), RuntimeException(marker))
        assertThat((check as CredentialCheck.Unreachable).reason).doesNotContain(marker)
    }
}
