package banghak.stock.core.port

import banghak.stock.core.domain.account.UserAccount
import banghak.stock.core.domain.identity.UserId

interface UserAccountPort {
    fun count(): Int

    fun findById(userId: UserId): UserAccount?

    fun save(account: UserAccount)
}
