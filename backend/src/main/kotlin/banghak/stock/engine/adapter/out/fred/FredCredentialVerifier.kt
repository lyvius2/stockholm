package banghak.stock.engine.adapter.out.fred

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

/**
 * SP500 최근 1건으로 키를 확인함.
 * FRED 는 잘못된 키에 400 을 돌려줌.
 * 키가 쿼리에 실리므로 예외 메시지를 내지 않음.
 */
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
                    asString(fields.getValue(CredentialFields.VALUE)),
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
            429 -> CredentialChecks.rateLimited()
            else -> CredentialCheck.Unreachable("HTTP ${response.code()}")
        }
    }

    fun unreachable(fields: Map<String, SecretValue>, cause: Throwable): CredentialCheck =
        CredentialChecks.unreachable(cause)

    companion object {
        private const val SERIES = "SP500"
    }
}
