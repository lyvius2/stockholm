package banghak.stock.engine.adapter.out.naver

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialFields
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.port.CredentialVerifier
import banghak.stock.engine.adapter.out.credential.CredentialChecks
import banghak.stock.engine.adapter.out.credential.RevealedSecrets.asString
import banghak.stock.shared.config.RuntimeProfiles
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

data class NaverSearchSummary(val total: Int = 0)

/**
 * 네이버 검색 API(뉴스).
 * 키 두 개는 헤더로 보냄.
 */
interface NaverSearchClient {
    @GET("v1/search/news.json")
    fun news(
        @Header("X-Naver-Client-Id") clientId: String,
        @Header("X-Naver-Client-Secret") clientSecret: String,
        @Query("query") query: String,
        @Query("display") display: Int,
    ): Call<NaverSearchSummary>
}

/**
 * 뉴스 검색 1건(삼성전자)으로 키를 확인함.
 * 틀린 키는 401 로 옴.
 *
 * TODO(학습 테스트로 확인): 개발자 문서를 자동으로 읽지 못해 엔드포인트·헤더 이름은 공개 문서 기억에 기댐.
 * 실제 키로 한 번 확인할 것
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class NaverCredentialVerifier(private val client: NaverSearchClient) : CredentialVerifier {
    override val kind = CredentialKind.NAVER

    @CircuitBreaker(name = "naver-verify", fallbackMethod = "unreachable")
    override fun verify(fields: Map<String, SecretValue>): CredentialCheck {
        val response =
            client
                .news(
                    asString(fields.getValue(CredentialFields.CLIENT_ID)),
                    asString(fields.getValue(CredentialFields.CLIENT_SECRET)),
                    PROBE_QUERY,
                    PROBE_DISPLAY,
                )
                .execute()
        if (!response.isSuccessful) return CredentialChecks.fromStatus(response)
        // 200 이어도 검색 결과가 없으면 응답 모양이 다른 것이라 검증됐다고 보지 않음
        val total = response.body()?.total ?: 0
        if (total < 1) return CredentialCheck.Unreachable("네이버 응답에 검색 결과가 없음")
        return CredentialCheck.Ok(mapOf("total" to total.toString()))
    }

    fun unreachable(fields: Map<String, SecretValue>, cause: Throwable): CredentialCheck =
        CredentialChecks.unreachable(cause)

    companion object {
        const val PROBE_QUERY = "삼성전자"
        const val PROBE_DISPLAY = 1
    }
}
