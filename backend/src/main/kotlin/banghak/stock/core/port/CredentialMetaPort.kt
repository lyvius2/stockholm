package banghak.stock.core.port

import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.CredentialMeta
import banghak.stock.core.domain.identity.UserId

interface CredentialMetaPort {
    fun upsert(meta: CredentialMeta)

    fun findShared(): List<CredentialMeta>

    fun findByUser(userId: UserId): List<CredentialMeta>

    fun delete(kind: CredentialKind, userId: UserId?)
}
