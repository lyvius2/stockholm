package banghak.stock.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethod

/** Retrofit 인터페이스를 알아보는 도우미. @GET·@POST 등은 `value`가 경로이고 @HTTP만 `path` 속성을 씀. */
object RetrofitEndpoints {
    private const val GENERIC_HTTP = "retrofit2.http.HTTP"
    private val HTTP_ANNOTATIONS =
        listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "HTTP").map {
            "retrofit2.http.$it"
        }

    fun pathOf(method: JavaMethod): String? {
        val annotation =
            method.annotations.firstOrNull { it.rawType.name in HTTP_ANNOTATIONS } ?: return null
        val property = if (annotation.rawType.name == GENERIC_HTTP) "path" else "value"
        val value = annotation.tryGetExplicitlyDeclaredProperty(property).orElse(null) ?: return ""
        return value.toString()
    }

    fun isEndpoint(method: JavaMethod): Boolean = pathOf(method) != null

    fun isRetrofitInterface(type: JavaClass): Boolean =
        type.isInterface && type.methods.any(::isEndpoint)
}
