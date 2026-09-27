package banghak.stock.core.usecase

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.CredentialMeta
import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.account.SecretValue

/** 공유 키 관리(admin + step-up)와 본인 토스 키 교체. 값은 어디에도 돌려주지 않음. */
interface CredentialAdminUseCase {
    fun sharedCredentials(admin: Principal): List<CredentialMeta>

    /** 검증 성공 값만 저장(교체). 실패하면 옛 값 유지. */
    fun replaceShared(
        admin: Principal,
        kind: CredentialKind,
        fields: Map<String, SecretValue>,
    ): CredentialCheck

    fun deleteShared(admin: Principal, kind: CredentialKind)

    fun recheckShared(admin: Principal, kind: CredentialKind): CredentialCheck

    /** 본인 토스 키 등록·교체. step-up 필요. */
    fun replaceOwnToss(principal: Principal, fields: Map<String, SecretValue>): CredentialCheck
}
