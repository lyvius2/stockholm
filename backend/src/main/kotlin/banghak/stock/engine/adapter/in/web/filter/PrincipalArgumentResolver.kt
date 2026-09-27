package banghak.stock.engine.adapter.`in`.web.filter

import banghak.stock.core.domain.account.Principal
import banghak.stock.shared.config.RuntimeProfiles
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Profile
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** 컨트롤러 메서드의 `Principal` 인자를 세션에서 채움. */
@Component
@Profile(RuntimeProfiles.ENGINE)
class PrincipalArgumentResolver : HandlerMethodArgumentResolver, WebMvcConfigurer {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == Principal::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any {
        val request =
            webRequest.getNativeRequest(HttpServletRequest::class.java)
                ?: throw IllegalStateException("HTTP 요청이 아님")
        return SessionAuth.principalOf(request)
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(this)
    }
}
