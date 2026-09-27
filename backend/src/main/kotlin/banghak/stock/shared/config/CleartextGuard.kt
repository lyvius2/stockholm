package banghak.stock.shared.config

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * `http://` 요청은 로컬 네트워크 주소에만 허용함.
 * 로컬 Ollama·캐시 서버·테스트 스텁은 평문이지만, 인터넷 주소로 키가 평문에 실리는 일은 설정 실수라도 막음.
 * 연결 전에 도는 애플리케이션 인터셉터라 DNS 조회 없이 호스트 문자열만 봄.
 */
object CleartextGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps && !isLocalNetworkHost(url.host)) {
            throw CleartextNotAllowedException(url.host)
        }
        return chain.proceed(chain.request())
    }

    fun isLocalNetworkHost(host: String): Boolean {
        val lower = host.lowercase().trimEnd('.')
        if (lower == "localhost" || lower.endsWith(".localhost")) return true
        if (lower == "::1" || lower.startsWith("fe80:")) return true
        if (lower.startsWith("127.")) return true
        if (isPrivateIpv4(lower)) return true
        if (!lower.contains('.')) return true
        return LOCAL_SUFFIXES.any { lower.endsWith(it) }
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val octets = host.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4) return false
        return octets[0] == 10 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 169 && octets[1] == 254)
    }

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".internal", ".home", ".home.arpa")
}

class CleartextNotAllowedException(host: String) : IOException("평문 HTTP 는 로컬 네트워크 주소에만 허용함: $host")
