package banghak.stock.engine.adapter.`in`.web.filter

import banghak.stock.core.domain.account.Principal
import banghak.stock.core.usecase.LoginUseCase
import banghak.stock.shared.config.RuntimeProfiles
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * `Authorization: Bearer <토큰>` 을 세션으로 바꿔 요청 속성에 둠.
 * 세션 없이 되는 경로(헬스·setup·로그인·사용자 목록·가입)를 뺀 나머지는 401.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class SessionAuth(private val login: LoginUseCase) : OncePerRequestFilter() {
    // 동의 콜백은 정확히 그 경로만 열고, 하위 경로는 열지 않음
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI == LocalTokenFilter.CONSENT_CALLBACK_PATH ||
            PUBLIC_PREFIXES.any { request.requestURI.startsWith(it) }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val principal = bearer(request)?.let(login::authenticate)
        if (principal == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "세션이 없거나 만료됨")
            return
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal)
        chain.doFilter(request, response)
    }

    companion object {
        const val PRINCIPAL_ATTRIBUTE = "stockholm.principal"
        private const val BEARER = "Bearer "
        private val PUBLIC_PREFIXES =
            listOf(
                "/actuator/health",
                "/setup",
                "/session/login",
                "/session/users",
                "/session/register",
            )

        fun bearer(request: HttpServletRequest): String? =
            request
                .getHeader("Authorization")
                ?.takeIf { it.startsWith(BEARER) }
                ?.removePrefix(BEARER)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        fun principalOf(request: HttpServletRequest): Principal =
            request.getAttribute(PRINCIPAL_ATTRIBUTE) as? Principal
                ?: throw banghak.stock.core.domain.error.SessionInvalidException("세션이 없음")
    }
}
