package banghak.stock.core.port

import banghak.stock.core.domain.account.RecoveryCode
import banghak.stock.core.domain.identity.UserId

interface RecoveryCodePort {
    fun replaceAll(userId: UserId, codes: List<RecoveryCode>)

    fun findUsableByUserId(userId: UserId): List<RecoveryCode>

    fun save(code: RecoveryCode)
}
