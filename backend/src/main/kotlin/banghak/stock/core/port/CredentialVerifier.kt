package banghak.stock.core.port

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretValue

interface CredentialVerifier {
    val kind: CredentialKind

    /** @param fields 필드 이름(`CredentialKind.fields`) → 값 */
    fun verify(fields: Map<String, SecretValue>): CredentialCheck
}
