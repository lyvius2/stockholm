package banghak.stock.learning

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag

/**
 * 외부 API 학습 테스트의 공통 규약. 키는 환경 변수(`STOCKHOLM_TEST_*`)로만 받고 없으면 건너뜀. scripts/learning-tests.sh 가
 * Keychain 의 `stockholm-dev` 항목에서 넣어 줌. 어떤 학습 테스트도 주문 엔드포인트를 부르지 못하게 HTTP 클라이언트가 경로를 막음.
 */
@Tag("learning")
abstract class LearningTestSupport {
    protected fun requireSecret(name: String): String {
        val value = System.getenv("STOCKHOLM_TEST_$name")
        assumeTrue(!value.isNullOrBlank(), "환경 변수 STOCKHOLM_TEST_$name 이 없어 건너뜀")
        return value.orEmpty()
    }

    protected fun httpClient(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(RefuseOrderEndpoints).build()

    private object RefuseOrderEndpoints : Interceptor {
        private val forbidden = listOf("/orders")

        override fun intercept(chain: Interceptor.Chain): Response {
            val path = chain.request().url.encodedPath
            check(forbidden.none { path.contains(it) }) { "학습 테스트는 주문 엔드포인트를 부를 수 없음: $path" }
            return chain.proceed(chain.request())
        }
    }
}
