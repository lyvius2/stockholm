package banghak.stock.engine.adapter.`in`.web.filter

import banghak.stock.core.domain.account.SetupState
import banghak.stock.core.usecase.LoginUseCase
import banghak.stock.core.usecase.SetupWizardUseCase
import banghak.stock.shared.config.RuntimeProfiles
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** 마법사가 끝나기 전에는 `/setup/…` 와 헬스 외 모든 API 가 403. 끝난 뒤에는 `/setup/…` 가 404(마법사는 다시 뜨지 않음). */
@Component
@Profile(RuntimeProfiles.ENGINE)
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class SetupGate(private val setup: SetupWizardUseCase, private val login: LoginUseCase) :
    OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI.startsWith(LocalTokenFilter.HEALTH_PATH)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val isSetupPath = request.requestURI.startsWith(SETUP_PREFIX)
        val state = setup.progress().state
        when {
            !state.isComplete && !isSetupPath ->
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "최초 구동 마법사를 먼저 끝내야 함")
            state.isComplete && isSetupPath -> response.sendError(HttpServletResponse.SC_NOT_FOUND)
            isSetupPath && needsWizardSession(state, request) && !hasAdminSession(request) ->
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "마법사 세션이 필요함")
            else -> chain.doFilter(request, response)
        }
    }

    // ① 이 끝난 뒤의 마법사 호출은 admin 비밀번호로 만든 세션이 있어야 함(다른 프로세스의 키 등록 방지)
    private fun needsWizardSession(state: SetupState, request: HttpServletRequest): Boolean =
        state.ordinal >= SetupState.ADMIN_CREATED.ordinal &&
            request.requestURI.removePrefix(SETUP_PREFIX) !in OPEN_SETUP_PATHS

    private fun hasAdminSession(request: HttpServletRequest): Boolean {
        val token = SessionAuth.bearer(request)
        val principal = token?.let(login::authenticate)
        return principal?.isAdmin == true
    }

    companion object {
        const val SETUP_PREFIX = "/setup"
        private val OPEN_SETUP_PATHS = setOf("/state", "/catalog", "/session")
    }
}
