package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialFields
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.port.CredentialVerifier
import banghak.stock.engine.adapter.out.credential.CredentialChecks
import banghak.stock.engine.adapter.out.credential.RevealedSecrets.asString
import banghak.stock.shared.config.RuntimeProfiles
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.ratelimiter.annotation.RateLimiter
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Header

/**
 * 아직 저장되지 않은 키로 계좌 목록을 읽는 클라이언트.
 * 저장된 키의 인증 인터셉터를 거치지 않도록 토큰을 헤더로 직접 받음.
 * 주문 경로는 없음.
 */
interface TossAccountProbeClient {
    @GET("api/v1/accounts")
    fun accounts(@Header("Authorization") bearer: String): Call<TossEnvelope<List<TossAccount>>>
}

/**
 * 토큰 발급 한 번과 계좌 목록 조회 한 번으로 토스 키를 확인함.
 * 주문 API 는 절대 부르지 않음.
 * 토스는 클라이언트당 유효 토큰이 하나라 이 발급으로 같은 키의 기존 토큰은 무효가 됨.
 * 데몬이 보관한 토큰은 다음 호출의 401 에서 한 번 갱신되므로 따로 손대지 않음.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class TossCredentialVerifier(
    private val auth: TossAuthClient,
    private val accounts: TossAccountProbeClient,
) : CredentialVerifier {
    override val kind = CredentialKind.TOSS

    @RateLimiter(name = "toss-account")
    @CircuitBreaker(name = "toss-verify", fallbackMethod = "unreachable")
    override fun verify(fields: Map<String, SecretValue>): CredentialCheck {
        val tokenResponse =
            auth
                .token(
                    GRANT_TYPE,
                    asString(fields.getValue(CredentialFields.CLIENT_ID)),
                    asString(fields.getValue(CredentialFields.CLIENT_SECRET)),
                )
                .execute()
        val token = tokenResponse.body()?.accessToken
        if (!tokenResponse.isSuccessful || token.isNullOrEmpty())
            return tokenFailure(tokenResponse.code())
        val accountsResponse = accounts.accounts("Bearer $token").execute()
        if (!accountsResponse.isSuccessful) return CredentialChecks.fromStatus(accountsResponse)
        // 결과가 아예 없는 것은 빈 계좌 목록이 아니라 응답 이상이므로 키 거부로 보지 않음
        val accounts =
            accountsResponse.body()?.result
                ?: return CredentialCheck.Unreachable("토스 계좌 응답에 결과가 없음")
        return brokerageCheck(accounts)
    }

    // 토스는 틀린 키를 400 으로도 돌려줌(OAuth invalid_client)
    private fun tokenFailure(status: Int): CredentialCheck =
        when (status) {
            400,
            401,
            403 -> CredentialCheck.Rejected("키가 틀렸거나 허용되지 않은 IP 에서 접속함")
            429 -> CredentialChecks.rateLimited()
            else -> CredentialCheck.Unreachable("토스 토큰 발급 실패(HTTP $status)")
        }

    // 계좌번호는 받지도 않으므로 detail 에는 순번과 개수만 둠
    private fun brokerageCheck(all: List<TossAccount>): CredentialCheck {
        val brokerage = all.filter { it.accountType == BROKERAGE }
        if (brokerage.isEmpty()) return CredentialCheck.Rejected("이 키로 쓸 수 있는 종합매매 계좌가 없음")
        val detail =
            mutableMapOf(
                "accountCount" to brokerage.size.toString(),
                "accountSeqs" to brokerage.joinToString(",") { it.accountSeq.toString() },
            )
        if (brokerage.size == 1) detail["accountSeq"] = brokerage.single().accountSeq.toString()
        return CredentialCheck.Ok(detail)
    }

    // 예외 메시지에 요청 본문(키)이 섞일 수 있어 종류 이름만 남김
    fun unreachable(fields: Map<String, SecretValue>, cause: Throwable): CredentialCheck =
        CredentialChecks.unreachable(cause)

    companion object {
        private const val GRANT_TYPE = "client_credentials"
        private const val BROKERAGE = "BROKERAGE"
    }
}
