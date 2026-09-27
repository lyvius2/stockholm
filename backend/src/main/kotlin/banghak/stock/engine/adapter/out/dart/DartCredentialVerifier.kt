package banghak.stock.engine.adapter.out.dart

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialFields
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.port.CredentialVerifier
import banghak.stock.engine.adapter.out.credential.CredentialChecks
import banghak.stock.shared.config.RuntimeProfiles
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 삼성전자 기업개황 1건으로 키를 확인함. 키가 쿼리 문자열에 실리므로 예외 메시지(URL 포함)를 밖으로 내지 않음. 상태 코드: 000 정상 · 010 미등록 키 · 011
 * 사용 불가 키 · 012 접근 불가 IP · 020 한도 초과 · 800 점검 중.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class DartCredentialVerifier(private val client: DartCompanyClient) : CredentialVerifier {
    override val kind = CredentialKind.DART

    @CircuitBreaker(name = "dart-verify", fallbackMethod = "unreachable")
    override fun verify(fields: Map<String, SecretValue>): CredentialCheck {
        val response =
            client
                .company(
                    String(fields.getValue(CredentialFields.VALUE).reveal()),
                    SAMSUNG_CORP_CODE,
                )
                .execute()
        if (!response.isSuccessful) return CredentialCheck.Unreachable("HTTP ${response.code()}")
        return when (val status = response.body()?.status) {
            "000" -> CredentialCheck.Ok(mapOf("status" to status))
            "010",
            "011" -> CredentialCheck.Rejected("키가 틀렸거나 사용할 수 없음")
            "012" -> CredentialCheck.Rejected("허용되지 않은 IP")
            "020" -> CredentialChecks.rateLimited()
            "800" -> CredentialCheck.Unreachable("DART 점검 중")
            else -> CredentialCheck.Unreachable("DART 응답 상태 ${status ?: "없음"}")
        }
    }

    fun unreachable(fields: Map<String, SecretValue>, cause: Throwable): CredentialCheck =
        CredentialChecks.unreachable(cause)

    companion object {
        const val SAMSUNG_CORP_CODE = "00126380"
    }
}
