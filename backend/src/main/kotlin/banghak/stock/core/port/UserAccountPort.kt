package banghak.stock.core.port

import banghak.stock.core.domain.account.UserAccount
import banghak.stock.core.domain.identity.UserId

/** 사용자 등록부. 설치 단위라 목록 조회가 정당함. */
interface UserAccountPort {
    fun count(): Int

    fun findAll(): List<UserAccount>

    fun findById(userId: UserId): UserAccount?

    fun findByDisplayName(displayName: String): UserAccount?

    fun save(account: UserAccount)
}
