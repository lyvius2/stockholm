package banghak.stock.engine.adapter.`in`.web.filter

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
class SetupGate(private val setup: SetupWizardUseCase) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI.startsWith(LocalTokenFilter.HEALTH_PATH)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val isSetupPath = request.requestURI.startsWith(SETUP_PREFIX)
        val complete = setup.progress().state.isComplete
        when {
            !complete && !isSetupPath ->
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "최초 구동 마법사를 먼저 끝내야 함")
            complete && isSetupPath -> response.sendError(HttpServletResponse.SC_NOT_FOUND)
            else -> chain.doFilter(request, response)
        }
    }

    companion object {
        const val SETUP_PREFIX = "/setup"
    }
}
