package banghak.stock.architecture.fixtures

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST

/** 경계 규칙이 우회 사례를 잡는지 확인하는 표본. 프로덕션 코드가 아니며 검사 대상 패키지에도 없음. */
interface OrdersByPost {
    @POST("v1/orders") fun place(): Call<String>
}

interface OrdersByGenericHttp {
    @HTTP(method = "POST", path = "v1/orders/{id}/cancel", hasBody = true)
    fun cancel(): Call<String>
}

interface QuotesOnly {
    @GET("v1/quotes") fun quotes(): Call<String>
}

class CallerWithGoodFallback(private val quotes: QuotesOnly) {
    @CircuitBreaker(name = "quotes", fallbackMethod = "quotesFallback")
    fun fetch(symbol: String): String = quotes.quotes().execute().body().orEmpty()

    @Suppress("unused") fun quotesFallback(symbol: String, cause: Throwable): String = "cached"
}

class CallerWithMismatchedFallback(private val quotes: QuotesOnly) {
    @CircuitBreaker(name = "quotes", fallbackMethod = "quotesFallback")
    fun fetch(symbol: String): String = quotes.quotes().execute().body().orEmpty()

    @Suppress("unused") fun quotesFallback(): String = "cached"
}

class CallerWithoutFallback(private val quotes: QuotesOnly) {
    fun fetch(): String = quotes.quotes().execute().body().orEmpty()
}
