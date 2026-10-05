package banghak.stock.engine.adapter.out.kftc

import banghak.stock.core.domain.account.CredentialFields
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.asset.AssetKind
import banghak.stock.core.domain.asset.ConsentStatus
import banghak.stock.core.domain.error.AssetUnavailableException
import banghak.stock.core.domain.error.ConsentRequiredException
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.engine.config.KftcProperties
import banghak.stock.support.MutableClock
import banghak.stock.support.VerifierStub
import banghak.stock.support.fakes.MemorySecretStore
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import java.time.Duration
import java.time.Instant
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.json.JsonMapper

/**
 * 동의 주소·토큰 교환·보관·갱신과 계좌 목록·잔액 조회의 매핑.
 * 계좌번호 원문은 어디에도 남지 않음.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KftcAssetAdapterTest {
    private val stub = VerifierStub()
    private val clock = MutableClock(Instant.parse("2026-10-06T00:00:00Z"))
    private val secrets = MemorySecretStore()
    private val userId = TradingFixtures.user
    private lateinit var adapter: KftcAssetAdapter
    private val clientId = "MARKER-KFTC-ID"
    private val clientSecret = "MARKER-KFTC-SECRET"

    @BeforeAll
    fun start() {
        stub.start()
        val properties =
            KftcProperties(
                baseUrl = "http://127.0.0.1:${stub.server.port()}/",
                redirectUri = "http://127.0.0.1:2609/assets/consent/callback",
                clientUseCode = "T991234567",
            )
        adapter =
            KftcAssetAdapter(
                KftcGateway(stub.client(), stub.client()),
                secrets,
                secrets,
                properties,
                JsonMapper.builder().build(),
                clock,
            )
    }

    @AfterAll fun stop() = stub.stop()

    @BeforeEach
    fun reset() {
        stub.reset()
        secrets.delete(SecretKey.user(userId, KftcAssetAdapter.TOKEN_NAME))
        secrets.put(
            SecretKey.shared(CredentialKind.KFTC_APP.secretName(CredentialFields.CLIENT_ID)),
            SecretValue.of(clientId),
        )
        secrets.put(
            SecretKey.shared(CredentialKind.KFTC_APP.secretName(CredentialFields.CLIENT_SECRET)),
            SecretValue.of(clientSecret),
        )
    }

    private fun stubToken(accessToken: String, expiresIn: Long = 7776000) =
        stub.respond(
            post(urlPathEqualTo("/oauth/2.0/token")),
            200,
            """{"access_token":"$accessToken","token_type":"Bearer","expires_in":$expiresIn,"refresh_token":"rt-1","scope":"login inquiry","user_seq_no":"U1"}""",
        )

    private fun stubAccounts(accessToken: String) {
        stub.respond(
            get(urlPathEqualTo("/v2.0/user/me"))
                .withHeader("Authorization", equalTo("Bearer $accessToken")),
            200,
            """{"rsp_code":"A0000","rsp_message":"","res_cnt":"2","res_list":[
                {"fintech_use_num":"F1","account_alias":"월급","bank_name":"국민은행","account_num_masked":"123-****-1234","account_type":"1","inquiry_agree_yn":"Y"},
                {"fintech_use_num":"F2","account_alias":"적금","bank_name":"신한은행","account_num_masked":"456-****-5678","account_type":"2","inquiry_agree_yn":"N"}]}""",
        )
        stub.respond(
            get(urlPathEqualTo("/v2.0/account/balance/fin_num"))
                .withQueryParam("fintech_use_num", equalTo("F1"))
                .withQueryParam("bank_tran_id", matching("T991234567U[0-9]{9}"))
                .withQueryParam("tran_dtime", matching("[0-9]{14}")),
            200,
            """{"rsp_code":"A0000","rsp_message":"","balance_amt":"1500000","available_amt":"1500000","product_name":"입출금"}""",
        )
    }

    @Test
    @DisplayName("동의 주소에 client_id·redirect·scope·state 가 실리고 콜백 코드로 받은 토큰을 Keychain 에 JSON 으로 보관함")
    fun authorizeAndComplete() {
        val url = adapter.authorizeUrl(userId, "state-1").toHttpUrl()
        assertThat(url.encodedPath).isEqualTo("/oauth/2.0/authorize")
        assertThat(url.queryParameter("client_id")).isEqualTo(clientId)
        assertThat(url.queryParameter("redirect_uri"))
            .isEqualTo("http://127.0.0.1:2609/assets/consent/callback")
        assertThat(url.queryParameter("state")).isEqualTo("state-1")
        assertThat(url.queryParameter("scope")).isEqualTo("login inquiry")
        assertThat(url.toString()).doesNotContain(clientSecret)

        stubToken("at-1")
        val consent = adapter.completeConsent(userId, "code-1")

        assertThat(consent.status).isEqualTo(ConsentStatus.ACTIVE)
        assertThat(consent.expiresAt).isEqualTo(clock.instant().plus(Duration.ofDays(90)))
        stub.server.verify(
            postRequestedFor(urlPathEqualTo("/oauth/2.0/token"))
                .withRequestBody(containing("code=code-1"))
                .withRequestBody(containing("client_secret=$clientSecret"))
                .withRequestBody(containing("grant_type=authorization_code"))
        )
        val stored = secrets.read(SecretKey.user(userId, KftcAssetAdapter.TOKEN_NAME))
        assertThat(String(stored!!.reveal()))
            .contains("\"accessToken\":\"at-1\"")
            .contains("\"userSeqNo\":\"U1\"")
        assertThat(adapter.consent(userId).status).isEqualTo(ConsentStatus.ACTIVE)
    }

    @Test
    @DisplayName("계좌 목록과 동의한 계좌의 잔액을 가려진 계좌 표기로 돌려주고, 조회 미동의 계좌는 잔액 없이 둠")
    fun assetsMapping() {
        stubToken("at-1")
        adapter.completeConsent(userId, "code-1")
        stubAccounts("at-1")

        val assets = adapter.assets(userId)

        assertThat(assets).hasSize(2)
        with(assets[0]) {
            assertThat(institution).isEqualTo("국민은행")
            assertThat(maskedAccount).isEqualTo("****1234")
            assertThat(kind).isEqualTo(AssetKind.DEPOSIT)
            assertThat(balance).isEqualTo(krw("1500000"))
        }
        with(assets[1]) {
            assertThat(kind).isEqualTo(AssetKind.SAVINGS)
            assertThat(balance).isNull()
        }
        stub.server.verify(1, getRequestedFor(urlPathEqualTo("/v2.0/account/balance/fin_num")))
    }

    @Test
    @DisplayName("동의가 없으면 ConsentRequired, 만료가 가까우면 refresh 토큰으로 갈아 끼우고, 401 은 동의 만료")
    fun tokenLifecycle() {
        assertThatThrownBy { adapter.assets(userId) }
            .isInstanceOf(ConsentRequiredException::class.java)
        assertThat(adapter.consent(userId).status).isEqualTo(ConsentStatus.NONE)

        stubToken("at-1", expiresIn = 600)
        adapter.completeConsent(userId, "code-1")
        clock.advance(Duration.ofMinutes(6))
        // 접근 토큰은 만료 직전이지만 동의는 살아 있음
        assertThat(adapter.consent(userId).status).isEqualTo(ConsentStatus.ACTIVE)
        stubToken("at-2", expiresIn = 7776000)
        stubAccounts("at-2")
        assertThat(adapter.assets(userId)).hasSize(2)
        stub.server.verify(
            postRequestedFor(urlPathEqualTo("/oauth/2.0/token"))
                .withRequestBody(containing("grant_type=refresh_token"))
                .withRequestBody(containing("refresh_token=rt-1"))
        )

        stub.respond(get(urlPathEqualTo("/v2.0/user/me")), 401, """{"rsp_code":"O0001"}""")
        assertThatThrownBy { adapter.assets(userId) }
            .isInstanceOf(ConsentRequiredException::class.java)

        adapter.revoke(userId)
        assertThat(adapter.consent(userId).status).isEqualTo(ConsentStatus.NONE)
    }

    @Test
    @DisplayName("동의 유효 기간(90일)이 지나면 토큰이 남아 있어도 EXPIRED 이고 조회는 다시 동의를 요구함")
    fun consentExpiryIsSeparateFromToken() {
        stubToken("at-1", expiresIn = 10 * 24 * 3600)
        adapter.completeConsent(userId, "code-1")

        clock.advance(Duration.ofDays(90))

        assertThat(adapter.consent(userId).status).isEqualTo(ConsentStatus.EXPIRED)
        assertThatThrownBy { adapter.assets(userId) }
            .isInstanceOf(ConsentRequiredException::class.java)
    }

    @Test
    @DisplayName("잔액 조회의 업무 오류는 잔액 없음이 아니라 조회 실패임")
    fun balanceBusinessErrorFails() {
        stubToken("at-1")
        adapter.completeConsent(userId, "code-1")
        stubAccounts("at-1")
        stub.respond(
            get(urlPathEqualTo("/v2.0/account/balance/fin_num")),
            200,
            """{"rsp_code":"A0021","rsp_message":"기관 오류"}""",
        )

        assertThatThrownBy { adapter.assets(userId) }
            .isInstanceOf(AssetUnavailableException::class.java)
            .hasMessageContaining("A0021")
    }

    @Test
    @DisplayName("기관 응답 코드가 성공이 아니거나 5xx 면 자산 조회 실패이고, 토큰 응답에 토큰이 없어도 실패")
    fun failures() {
        stubToken("at-1")
        adapter.completeConsent(userId, "code-1")
        stub.respond(
            get(urlPathEqualTo("/v2.0/user/me")),
            200,
            """{"rsp_code":"A0002","rsp_message":"오류"}""",
        )
        assertThatThrownBy { adapter.assets(userId) }
            .isInstanceOf(AssetUnavailableException::class.java)
        stub.respond(get(urlPathEqualTo("/v2.0/user/me")), 503, "{}")
        assertThatThrownBy { adapter.assets(userId) }
            .isInstanceOf(AssetUnavailableException::class.java)

        stub.respond(
            post(urlPathEqualTo("/oauth/2.0/token")),
            200,
            """{"rsp_code":"O0001","rsp_message":"bad code"}""",
        )
        assertThatThrownBy { adapter.completeConsent(userId, "bad") }
            .isInstanceOf(AssetUnavailableException::class.java)
            .hasMessageNotContaining("bad")
    }
}
