package banghak.stock.engine.adapter.`in`.web.filter

import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.web.LocalToken
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 헬스와 금융결제원 동의 콜백을 뺀 모든 로컬 API 는 기동 시 만든 로컬 토큰 헤더가 있어야 함.
 * 없으면 401.
 * 콜백은 브라우저가 부르므로 헤더가 없고, 1회용 state 가 사용자를 증명함.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
@Order(Ordered.HIGHEST_PRECEDENCE)
class LocalTokenFilter(private val token: LocalToken) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI.startsWith(HEALTH_PATH) || request.requestURI == CONSENT_CALLBACK_PATH

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        if (!token.matches(request.getHeader(LocalToken.HEADER))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "로컬 토큰이 없거나 틀림")
            return
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val HEALTH_PATH = "/actuator/health"
        const val CONSENT_CALLBACK_PATH = "/assets/consent/callback"
    }
}
