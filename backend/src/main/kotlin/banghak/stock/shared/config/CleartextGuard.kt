package banghak.stock.shared.config

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * `http://` 요청은 접속 대상 주소가 루프백·사설·링크로컬 범위일 때만 허용함.
 * 로컬 Ollama·캐시 서버·테스트 스텁은 평문이지만, 인터넷 주소로 키가 평문에 실리는 일은 설정 실수라도 막음.
 * 호스트 이름은 실제로 풀어(`resolve`) 모든 주소가 로컬 범위인지 봄.
 * 풀리지 않는 이름도 거부함.
 */
class CleartextGuard(private val resolve: (String) -> List<InetAddress> = ::resolveWithSystemDns) :
    Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps && !isLocalNetworkHost(url.host)) {
            throw CleartextNotAllowedException(url.host)
        }
        return chain.proceed(chain.request())
    }

    fun isLocalNetworkHost(host: String): Boolean {
        val addresses =
            try {
                resolve(host)
            } catch (e: UnknownHostException) {
                return false
            }
        return addresses.isNotEmpty() && addresses.all(::isLocalNetworkAddress)
    }

    companion object {
        fun isLocalNetworkAddress(address: InetAddress): Boolean =
            address.isLoopbackAddress ||
                address.isSiteLocalAddress ||
                address.isLinkLocalAddress ||
                isIpv6UniqueLocal(address)

        // fc00::/7.
        // `isSiteLocalAddress` 는 옛 fec0::/10 만 봄
        private fun isIpv6UniqueLocal(address: InetAddress): Boolean {
            val bytes = address.address
            return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
        }

        private fun resolveWithSystemDns(host: String): List<InetAddress> =
            InetAddress.getAllByName(host).toList()
    }
}

class CleartextNotAllowedException(host: String) : IOException("평문 HTTP 는 로컬 네트워크 주소에만 허용함: $host")
