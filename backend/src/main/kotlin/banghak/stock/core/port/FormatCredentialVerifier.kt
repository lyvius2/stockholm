package banghak.stock.core.port

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretValue

/**
 * 형식만 검사하는 검증기.
 * 실제 API 를 부르는 어댑터가 없는 종류(SEC 이메일·금융결제원 앱·캐시 서버)의 기본값임.
 * 외부 호출이 없으므로 주문 엔드포인트를 부를 일도 없음.
 */
class FormatCredentialVerifier(override val kind: CredentialKind) : CredentialVerifier {
    override fun verify(fields: Map<String, SecretValue>): CredentialCheck {
        val missing = kind.fields.filterNot { it in fields }
        if (missing.isNotEmpty())
            return CredentialCheck.Rejected("입력이 빠짐: ${missing.joinToString()}")
        return when (kind) {
            CredentialKind.SEC_CONTACT_EMAIL -> check(fields, EMAIL, "이메일 형식이 아님")
            CredentialKind.OLLAMA,
            CredentialKind.CACHE_SERVER ->
                check(fields, URL_OR_HOST, "주소 형식이 아님(예: http://127.0.0.1:11434)")
            else -> check(fields, TOKEN, "키는 공백 없이 ${MIN_TOKEN}자 이상이어야 함")
        }
    }

    private fun check(
        fields: Map<String, SecretValue>,
        pattern: Regex,
        reason: String,
    ): CredentialCheck {
        val allValid = fields.values.all { value -> value.matches(pattern) }
        return if (allValid) CredentialCheck.Ok(mapOf("verification" to "format-only"))
        else CredentialCheck.Rejected(reason)
    }

    companion object {
        private const val MIN_TOKEN = 8
        private val TOKEN = Regex("\\S{$MIN_TOKEN,512}")
        private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
        private val URL_OR_HOST =
            Regex("(https?://|redis://|rediss://)?[A-Za-z0-9.\\-]+(:[0-9]{2,5})?(/.*)?")
    }
}
