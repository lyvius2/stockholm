package banghak.stock.learning

import javax.net.ssl.SSLHandshakeException
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * DART 서버의 TLS 구성 학습 테스트. 키 없이 기업개황 1건을 부르며 응답 본문은 보지 않음. 기본 OkHttp 규칙이 통하기 시작하면 `TlsPolicy` 의 완화가
 * 더 필요 없다는 뜻임.
 */
class DartTlsLearningTest : LearningTestSupport() {
    private val request =
        Request.Builder()
            .url("https://opendart.fss.or.kr/api/company.json?crtfc_key=none&corp_code=00126380")
            .build()

    @Test
    @DisplayName("DART 는 JDK 가 켜 둔 DHE 묶음으로 TLS 1.2 접속이 된다")
    fun connectsWithJdkEnabledCipherSuites() {
        httpClient().newCall(request).execute().use { response ->
            val handshake = requireNotNull(response.handshake)
            assertThat(response.code).isEqualTo(200)
            assertThat(handshake.cipherSuite.javaName).startsWith("TLS_DHE_RSA_")
        }
    }

    @Test
    @DisplayName("DART 는 OkHttp 기본 규칙(ECDHE·TLS 1.3 만)으로는 handshake_failure 를 낸다")
    fun rejectsOkHttpDefaultConnectionSpec() {
        val defaultClient =
            OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.MODERN_TLS)).build()

        assertThatThrownBy { defaultClient.newCall(request).execute().close() }
            .isInstanceOf(SSLHandshakeException::class.java)
    }
}
