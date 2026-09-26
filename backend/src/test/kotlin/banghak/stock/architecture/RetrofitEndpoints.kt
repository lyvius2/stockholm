package banghak.stock.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethod

/** Retrofit 인터페이스를 알아보는 도우미. HTTP 메서드 애너테이션의 값이 경로임. */
object RetrofitEndpoints {
    private val HTTP_ANNOTATIONS =
        listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "HTTP").map {
            "retrofit2.http.$it"
        }

    fun pathOf(method: JavaMethod): String? {
        val annotation =
            method.annotations.firstOrNull { it.rawType.name in HTTP_ANNOTATIONS } ?: return null
        val value = annotation.tryGetExplicitlyDeclaredProperty("value").orElse(null) ?: return ""
        return value.toString()
    }

    fun isEndpoint(method: JavaMethod): Boolean = pathOf(method) != null

    fun isRetrofitInterface(type: JavaClass): Boolean =
        type.isInterface && type.methods.any(::isEndpoint)
}
