package banghak.stock.engine.adapter.`in`.web.filter

import banghak.stock.core.domain.account.Principal
import banghak.stock.core.usecase.LoginUseCase
import banghak.stock.shared.web.LocalToken
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/** 금융결제원 동의 콜백만 로컬 토큰·세션 없이 들어오고, 비슷한 경로는 여전히 막힘. */
class ConsentCallbackExemptionTest {
    // 로컬 토큰은 어떤 값도 맞지 않고, 세션은 어떤 토큰도 인증되지 않음
    private val localToken = LocalTokenFilter(mock(LocalToken::class.java))
    private val sessionAuth =
        SessionAuth(
            object : LoginUseCase by mock(LoginUseCase::class.java) {
                override fun authenticate(token: String): Principal? = null
            }
        )

    private fun passes(filter: jakarta.servlet.Filter, path: String): Boolean {
        val chain = MockFilterChain()
        val response = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("GET", path), response, chain)
        return chain.request != null && response.status == 200
    }

    @Test
    @DisplayName("콜백 경로는 두 필터를 헤더·세션 없이 지남")
    fun callbackPassesBothFilters() {
        assertThat(passes(localToken, "/assets/consent/callback")).isTrue()
        assertThat(passes(sessionAuth, "/assets/consent/callback")).isTrue()
    }

    @Test
    @DisplayName("자산 조회·동의 시작·콜백 하위 경로는 그대로 401")
    fun otherAssetPathsStayProtected() {
        listOf(
                "/assets",
                "/assets/consent",
                "/assets/consent/callback/x",
                "/assets/consent/callbacks",
            )
            .forEach { path ->
                assertThat(passes(localToken, path)).describedAs(path).isFalse()
                assertThat(passes(sessionAuth, path)).describedAs(path).isFalse()
            }
    }
}
