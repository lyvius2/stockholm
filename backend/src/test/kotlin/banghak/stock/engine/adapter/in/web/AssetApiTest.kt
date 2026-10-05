package banghak.stock.engine.adapter.`in`.web

import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.asset.AssetConsent
import banghak.stock.core.domain.asset.AssetKind
import banghak.stock.core.domain.asset.AssetSnapshot
import banghak.stock.core.domain.asset.ConsentStart
import banghak.stock.core.domain.asset.ExternalAsset
import banghak.stock.core.domain.error.ConsentRequiredException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.usecase.AssetConsentUseCase
import banghak.stock.core.usecase.LookupAssetsUseCase
import banghak.stock.engine.adapter.`in`.web.asset.AssetController
import banghak.stock.support.web.ApiTestSupport
import banghak.stock.support.web.ApiTestSupport.assertConforms
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 자산 REST 와 브라우저 콜백.
 * 응답은 스키마에 맞고 콜백은 state 만으로 사용자를 찾음.
 */
class AssetApiTest {
    private val now = Instant.parse("2026-10-06T00:00:00Z")
    private val completed = mutableListOf<Pair<String, String>>()
    private val abandoned = mutableListOf<String>()
    private val revoked = mutableListOf<UserId>()
    private var snapshot: () -> AssetSnapshot = {
        AssetSnapshot(
            listOf(ExternalAsset("국민은행", "****1234", AssetKind.DEPOSIT, krw("1500000"), now)),
            now,
        )
    }
    private val assets =
        object : LookupAssetsUseCase {
            override fun assets(userId: UserId) = snapshot()
        }
    private val consents =
        object : AssetConsentUseCase {
            override fun start(principal: Principal) =
                ConsentStart("https://kftc/authorize?state=s-1")

            override fun complete(state: String, code: String): UserId {
                if (state != "s-1") throw InvalidValueException("모르는 state")
                completed += state to code
                return TradingFixtures.user
            }

            override fun abandon(state: String) {
                abandoned += state
            }

            override fun status(userId: UserId) =
                AssetConsent.of(now, Instant.parse("2027-01-04T00:00:00Z"), now)

            override fun revoke(principal: Principal) {
                revoked += principal.userId
            }
        }
    private val mvc = ApiTestSupport.mockMvc(AssetController(assets, consents))

    @Test
    @DisplayName("자산 목록은 가려진 계좌와 잔액을 돌려주고, 동의가 없으면 428")
    fun assetsAndConsentRequired() {
        val body =
            mvc.perform(get("/assets"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.assets[0].maskedAccount").value("****1234"))
                .andExpect(jsonPath("$.assets[0].balance.amount").value("1500000"))
                .andExpect(jsonPath("$.isStale").value(false))
                .andReturn()
                .response
                .contentAsString
        assertConforms(body, "api-asset-snapshot")

        snapshot = { throw ConsentRequiredException("동의 없음") }
        mvc.perform(get("/assets"))
            .andExpect(status().isPreconditionRequired)
            .andExpect(jsonPath("$.code").value("ConsentRequiredException"))
    }

    @Test
    @DisplayName("동의 상태·시작·철회")
    fun consentEndpoints() {
        val status =
            mvc.perform(get("/assets/consent"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn()
                .response
                .contentAsString
        assertConforms(status, "api-asset-consent")

        val start =
            mvc.perform(post("/assets/consent"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.authorizeUrl").value("https://kftc/authorize?state=s-1"))
                .andReturn()
                .response
                .contentAsString
        assertConforms(start, "api-asset-consent-start")

        mvc.perform(delete("/assets/consent")).andExpect(status().isOk)
        assertThat(revoked).containsExactly(TradingFixtures.user)
    }

    @Test
    @DisplayName("콜백은 state 와 code 로 완료하고, 거절·오류·모르는 state 는 토큰을 만들지 않음")
    fun callback() {
        mvc.perform(get("/assets/consent/callback").param("state", "s-1").param("code", "c-1"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("끝났습니다")))
        assertThat(completed).containsExactly("s-1" to "c-1")

        mvc.perform(
                get("/assets/consent/callback")
                    .param("state", "s-2")
                    .param("error", "access_denied")
            )
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("완료되지 않았습니다")))
        assertThat(abandoned).containsExactly("s-2")

        mvc.perform(get("/assets/consent/callback").param("state", "bad").param("code", "c-9"))
            .andExpect(status().isBadRequest)
            .andExpect(
                content()
                    .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("c-9")))
            )

        mvc.perform(get("/assets/consent/callback").param("code", "c-9"))
            .andExpect(status().isBadRequest)
        assertThat(completed).hasSize(1)
    }
}
