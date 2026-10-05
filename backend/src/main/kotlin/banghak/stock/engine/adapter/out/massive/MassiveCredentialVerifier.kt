package banghak.stock.engine.adapter.out.massive

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
import retrofit2.http.Path

/**
 * Massive 응답은 `status` 와 `results` 를 가짐.
 * 검증은 상태만 봄.
 */
data class MassiveStatus(val status: String = "")

/**
 * 종목 참조(REFERENCE) 엔드포인트.
 * 키는 쿼리가 아니라 헤더로 보내 URL 에 남지 않게 함.
 */
interface MassiveReferenceClient {
    @GET("v3/reference/tickers/{ticker}")
    fun ticker(
        @Header("Authorization") authorization: String,
        @Path("ticker") ticker: String,
    ): Call<MassiveStatus>
}

/**
 * 종목 개요 1건(AAPL)으로 키를 확인함.
 * 무료 등급은 분당 5회라 429 는 키 문제가 아님.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class MassiveCredentialVerifier(private val client: MassiveReferenceClient) : CredentialVerifier {
    override val kind = CredentialKind.MASSIVE

    @CircuitBreaker(name = "massive-verify", fallbackMethod = "unreachable")
    override fun verify(fields: Map<String, SecretValue>): CredentialCheck {
        val response =
            client
                .ticker("Bearer " + asString(fields.getValue(CredentialFields.VALUE)), PROBE_TICKER)
                .execute()
        if (!response.isSuccessful) return CredentialChecks.fromStatus(response)
        // 200 이어도 본문이 비었거나 상태가 OK 가 아니면 검증됐다고 보지 않음
        val status = response.body()?.status
        if (status != OK_STATUS)
            return CredentialCheck.Unreachable("Massive 응답 상태 ${status ?: "없음"}")
        return CredentialCheck.Ok(mapOf("status" to status))
    }

    fun unreachable(fields: Map<String, SecretValue>, cause: Throwable): CredentialCheck =
        CredentialChecks.unreachable(cause)

    companion object {
        const val PROBE_TICKER = "AAPL"
        private const val OK_STATUS = "OK"
    }
}
