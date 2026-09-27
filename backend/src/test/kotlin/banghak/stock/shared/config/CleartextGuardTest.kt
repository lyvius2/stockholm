package banghak.stock.shared.config

import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CleartextGuardTest {
    @Test
    @DisplayName("루프백·사설 IP·로컬 접미사 호스트만 평문을 허용함")
    fun allowsOnlyLocalNetworkHosts() {
        for (host in
            listOf(
                "localhost",
                "127.0.0.1",
                "::1",
                "10.0.0.5",
                "192.168.1.20",
                "172.16.0.1",
                "172.31.255.255",
                "ollama",
                "mac-mini.local",
                "nas.lan",
            )) assertThat(CleartextGuard.isLocalNetworkHost(host)).describedAs(host).isTrue()
        for (host in
            listOf(
                "opendart.fss.or.kr",
                "8.8.8.8",
                "172.32.0.1",
                "192.169.0.1",
                "example.com",
            )) assertThat(CleartextGuard.isLocalNetworkHost(host)).describedAs(host).isFalse()
    }

    @Test
    @DisplayName("공용 클라이언트는 인터넷 주소로의 http 요청을 연결 전에 막음")
    fun sharedClientRefusesPublicCleartext() {
        val client = OkHttpConfig().okHttpClient(HttpProperties())

        assertThatThrownBy {
                client
                    .newCall(Request.Builder().url("http://example.com/").build())
                    .execute()
                    .close()
            }
            .isInstanceOf(CleartextNotAllowedException::class.java)
    }
}
