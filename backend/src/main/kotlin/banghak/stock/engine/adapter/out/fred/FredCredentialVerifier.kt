package banghak.stock.engine.adapter.out.fred

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialFields
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.port.CredentialVerifier
import banghak.stock.shared.config.RuntimeProfiles
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** SP500 최근 1건으로 키를 확인함. FRED 는 잘못된 키에 400 을 돌려줌. 키가 쿼리에 실리므로 예외 메시지를 내지 않음. */
@Component
@Profile(RuntimeProfiles.ENGINE)
class FredCredentialVerifier(private val client: FredObservationsClient) : CredentialVerifier {
    override val kind = CredentialKind.FRED

    @CircuitBreaker(name = "fred-verify", fallbackMethod = "unreachable")
    override fun verify(fields: Map<String, SecretValue>): CredentialCheck {
        val response =
            client
                .observations(
                    SERIES,
                    String(fields.getValue(CredentialFields.VALUE).reveal()),
                    "json",
                    "desc",
                    1,
                )
                .execute()
        response.body()?.close()
        return when (response.code()) {
            in 200..299 -> CredentialCheck.Ok(mapOf("series" to SERIES))
            400,
            401,
            403 -> CredentialCheck.Rejected("키가 틀렸거나 등록되지 않음")
            429 -> CredentialCheck.Rejected("호출 한도 초과, 잠시 뒤 다시")
            else -> CredentialCheck.Unreachable("HTTP ${response.code()}")
        }
    }

    fun unreachable(fields: Map<String, SecretValue>, cause: Throwable): CredentialCheck =
        CredentialCheck.Unreachable("연결할 수 없음 (${cause::class.simpleName})")

    companion object {
        private const val SERIES = "SP500"
    }
}
