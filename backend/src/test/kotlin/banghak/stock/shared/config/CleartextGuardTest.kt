package banghak.stock.shared.config

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CleartextGuardTest {
    private val table =
        mapOf(
            "localhost" to listOf("127.0.0.1", "::1"),
            "nas.lan" to listOf("192.168.1.20"),
            "ollama" to listOf("10.0.0.5"),
            "dual.example" to listOf("10.0.0.5", "93.184.216.34"),
            "127.example.com" to listOf("93.184.216.34"),
            "example.com" to listOf("93.184.216.34"),
            "fd00.example" to listOf("fd12:3456::1"),
        )
    private val guard = CleartextGuard { host ->
        val literal = runCatching {
            InetAddress.getByName(host).takeIf { host.matches(Regex("[0-9a-fA-F:.]+")) }
        }
            .getOrNull()
        if (literal != null) listOf(literal)
        else table[host]?.map(InetAddress::getByName) ?: throw UnknownHostException(host)
    }

    @Test
    @DisplayName("접속 대상 주소가 전부 루프백·사설·링크로컬·ULA 범위일 때만 평문을 허용함")
    fun allowsOnlyHostsThatResolveToLocalRanges() {
        for (host in
            listOf(
                "localhost",
                "127.0.0.1",
                "::1",
                "10.0.0.5",
                "192.168.1.20",
                "172.16.0.1",
                "172.31.255.255",
                "169.254.1.1",
                "nas.lan",
                "ollama",
                "fd00.example",
            )) assertThat(guard.isLocalNetworkHost(host)).describedAs(host).isTrue()
        for (host in
            listOf(
                "8.8.8.8",
                "172.32.0.1",
                "192.169.0.1",
                "example.com",
                "127.example.com",
                "dual.example",
                "no-such-host.invalid",
            )) assertThat(guard.isLocalNetworkHost(host)).describedAs(host).isFalse()
    }

    @Test
    @DisplayName("공인 주소로 풀리는 http 요청은 연결 전에 막고, 공용 클라이언트에 이 인터셉터가 들어 있음")
    fun refusesPublicCleartextBeforeConnecting() {
        val client = OkHttpClient.Builder().addInterceptor(guard).build()

        assertThatThrownBy {
                client
                    .newCall(Request.Builder().url("http://127.example.com/").build())
                    .execute()
                    .close()
            }
            .isInstanceOf(CleartextNotAllowedException::class.java)
        assertThat(OkHttpConfig().okHttpClient(HttpProperties()).interceptors).anyMatch {
            it is CleartextGuard
        }
    }
}
