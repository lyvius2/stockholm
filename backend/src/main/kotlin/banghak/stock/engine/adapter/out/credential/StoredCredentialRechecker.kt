package banghak.stock.engine.adapter.out.credential

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.port.CredentialRecheckPort
import banghak.stock.core.port.CredentialVerifier
import banghak.stock.engine.adapter.out.keychain.SecretReader
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** 저장된 값을 Keychain 에서 꺼내 검증기에 넘기고 곧바로 지움. 값은 이 클래스 밖으로 나가지 않음. */
@Component
@Profile(RuntimeProfiles.ENGINE)
class StoredCredentialRechecker(
    private val reader: SecretReader,
    verifiers: List<CredentialVerifier>,
) : CredentialRecheckPort {
    private val verifierByKind = verifiers.associateBy { it.kind }

    override fun recheck(kind: CredentialKind, userId: UserId?): CredentialCheck {
        val verifier = verifierByKind[kind] ?: return CredentialCheck.Rejected("검증기가 없음")
        val fields = mutableMapOf<String, SecretValue>()
        for (field in kind.fields) {
            val key =
                if (userId == null) SecretKey.shared(kind.secretName(field))
                else SecretKey.user(userId, kind.secretName(field))
            fields[field] = reader.read(key) ?: return CredentialCheck.Rejected("저장된 키가 없음")
        }
        return try {
            verifier.verify(fields)
        } finally {
            fields.values.forEach { it.wipe() }
        }
    }
}
