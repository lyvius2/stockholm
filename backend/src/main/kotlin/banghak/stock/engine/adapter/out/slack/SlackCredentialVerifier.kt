package banghak.stock.engine.adapter.out.slack

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
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Slack 은 HTTP 200 에 `ok` 로 성패를 알림.
 * 실패면 `error` 에 코드(`invalid_auth` 등).
 */
data class SlackAuthTest(
    val ok: Boolean = false,
    val error: String? = null,
    val team: String? = null,
    val user: String? = null,
)

interface SlackAuthClient {
    @POST("api/auth.test")
    fun authTest(@Header("Authorization") bearer: String): Call<SlackAuthTest>
}

/**
 * `auth.test` 로 봇 토큰을 확인함.
 * 워크스페이스 이름만 detail 에 남김.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class SlackCredentialVerifier(private val client: SlackAuthClient) : CredentialVerifier {
    override val kind = CredentialKind.SLACK

    @CircuitBreaker(name = "slack-verify", fallbackMethod = "unreachable")
    override fun verify(fields: Map<String, SecretValue>): CredentialCheck {
        val response =
            client.authTest("Bearer ${asString(fields.getValue(CredentialFields.VALUE))}").execute()
        if (!response.isSuccessful) return CredentialChecks.fromStatus(response)
        val body = response.body() ?: return CredentialCheck.Unreachable("Slack 응답이 비어 있음")
        if (!body.ok) return CredentialCheck.Rejected("Slack 이 토큰을 거부함(${body.error ?: "사유 없음"})")
        return CredentialCheck.Ok(
            listOfNotNull(body.team?.let { "team" to it }, body.user?.let { "user" to it }).toMap()
        )
    }

    fun unreachable(fields: Map<String, SecretValue>, cause: Throwable): CredentialCheck =
        CredentialChecks.unreachable(cause)
}
