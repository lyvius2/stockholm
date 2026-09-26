package banghak.stock.support.fakes

import banghak.stock.core.domain.account.AuditEntry
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.CredentialMeta
import banghak.stock.core.domain.account.Installation
import banghak.stock.core.domain.account.TotpEnrollment
import banghak.stock.core.domain.account.UserAccount
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.port.AuditLogPort
import banghak.stock.core.port.CredentialMetaPort
import banghak.stock.core.port.InstallationPort
import banghak.stock.core.port.PasswordHasherPort
import banghak.stock.core.port.TotpPort
import banghak.stock.core.port.UserAccountPort
import java.time.Instant

class MemoryInstallationPort : InstallationPort {
    var installation: Installation? = null

    override fun load(): Installation? = installation

    override fun save(installation: Installation) {
        this.installation = installation
    }
}

class MemoryUserAccountPort : UserAccountPort {
    val accounts = mutableMapOf<UserId, UserAccount>()

    override fun count(): Int = accounts.size

    override fun findById(userId: UserId): UserAccount? = accounts[userId]

    override fun save(account: UserAccount) {
        accounts[account.userId] = account
    }
}

class MemoryCredentialMetaPort : CredentialMetaPort {
    val metas = mutableMapOf<Pair<CredentialKind, UserId?>, CredentialMeta>()

    override fun upsert(meta: CredentialMeta) {
        metas[meta.kind to meta.userId] = meta
    }

    override fun findShared(): List<CredentialMeta> = metas.values.filter { it.userId == null }

    override fun findByUser(userId: UserId): List<CredentialMeta> =
        metas.values.filter { it.userId == userId }

    override fun delete(kind: CredentialKind, userId: UserId?) {
        metas.remove(kind to userId)
    }
}

class MemoryAuditLogPort : AuditLogPort {
    val entries = mutableListOf<AuditEntry>()

    override fun record(entry: AuditEntry) {
        entries += entry
    }
}

/** 해시는 접두어만 붙임. 테스트에서 원문이 그대로 남지 않는지 확인하지 않을 때만 씀. */
class FakePasswordHasher : PasswordHasherPort {
    override fun hash(password: CharArray): String = "hashed:" + String(password).reversed()

    override fun matches(password: CharArray, hash: String): Boolean = hash == hash(password)
}

/** 고정 코드 하나만 맞는 TOTP. */
class FakeTotpPort(private val validCode: String = "123456") : TotpPort {
    val enrolled = mutableListOf<UserId>()

    override fun enroll(userId: UserId, accountLabel: String): TotpEnrollment {
        enrolled += userId
        return TotpEnrollment(byteArrayOf(1, 2, 3), "FAKESEED")
    }

    override fun verify(userId: UserId, code: String, now: Instant): Long? =
        if (userId in enrolled && code == validCode) now.epochSecond / 30 else null
}
