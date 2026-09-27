package banghak.stock.shared.config

import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class OkHttpConfigTest {
    private val client = OkHttpConfig().okHttpClient(HttpProperties())

    @Test
    @DisplayName("공용 클라이언트는 TLS 1.2·1.3만 쓰고 암호 묶음은 JDK 정책에 맡긴다")
    fun offersEveryJdkEnabledCipherSuiteOnTls12And13() {
        val (spec, cleartext) = client.connectionSpecs

        assertThat(cleartext).isEqualTo(ConnectionSpec.CLEARTEXT)
        assertThat(spec.isTls).isTrue()
        assertThat(spec.tlsVersions)
            .containsExactlyInAnyOrder(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        assertThat(spec.cipherSuites).describedAs("null 이면 소켓이 켜 둔 묶음 전부를 제시함").isNull()
    }
}
