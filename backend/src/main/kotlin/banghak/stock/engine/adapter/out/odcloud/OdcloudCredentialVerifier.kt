package banghak.stock.engine.adapter.out.odcloud

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
import retrofit2.http.Query

data class OdcloudPage(val currentCount: Int = 0, val totalCount: Int = 0)

/**
 * 공공데이터포털(odcloud) 국민연금 해외주식 데이터셋.
 * 연도마다 경로(uddi)가 따로 있어 검증은 2017년 말 데이터셋 하나만 부름.
 * 출처: https://infuser.odcloud.kr/oas/docs?namespace=3070517/v1
 */
interface OdcloudNpsClient {
    @GET("api/3070517/v1/uddi:b9470910-bcb7-4048-82b6-d21d561594ea_201812140934")
    fun holdings2017(
        @Query("serviceKey") serviceKey: String,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
    ): Call<OdcloudPage>
}

/**
 * 데이터셋 1행으로 서비스 키를 확인함.
 * 키가 쿼리에 실리므로 예외 메시지(URL)를 밖으로 내지 않음.
 * 포털이 주는 키 두 가지 중 "디코딩" 키를 받아야 함(Retrofit 이 다시 인코딩함).
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class OdcloudCredentialVerifier(private val client: OdcloudNpsClient) : CredentialVerifier {
    override val kind = CredentialKind.ODCLOUD

    @CircuitBreaker(name = "odcloud-verify", fallbackMethod = "unreachable")
    override fun verify(fields: Map<String, SecretValue>): CredentialCheck {
        val response =
            client.holdings2017(asString(fields.getValue(CredentialFields.VALUE)), 1, 1).execute()
        if (!response.isSuccessful) return CredentialChecks.fromStatus(response)
        // 200 이어도 행이 없으면 데이터셋 쪽 문제일 수 있어 검증됐다고 보지 않음
        val page = response.body() ?: return CredentialCheck.Unreachable("공공데이터포털 응답이 비어 있음")
        if (page.currentCount < 1) return CredentialCheck.Unreachable("공공데이터포털 응답에 행이 없음")
        return CredentialCheck.Ok(mapOf("totalCount" to page.totalCount.toString()))
    }

    fun unreachable(fields: Map<String, SecretValue>, cause: Throwable): CredentialCheck =
        CredentialChecks.unreachable(cause)
}
