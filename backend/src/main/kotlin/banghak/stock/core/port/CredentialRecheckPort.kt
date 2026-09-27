package banghak.stock.core.port

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.identity.UserId

/** 저장된 키를 다시 검증함. 값은 어댑터 안에서만 읽음. 없으면 Rejected. */
interface CredentialRecheckPort {
    fun recheck(kind: CredentialKind, userId: UserId?): CredentialCheck
}
