package banghak.stock.support.fakes

import banghak.stock.core.domain.account.AuditEntry
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.CredentialMeta
import banghak.stock.core.domain.account.Device
import banghak.stock.core.domain.account.Installation
import banghak.stock.core.domain.account.RecoveryCode
import banghak.stock.core.domain.account.RegistrationCode
import banghak.stock.core.domain.account.Session
import banghak.stock.core.domain.account.TotpEnrollment
import banghak.stock.core.domain.account.UserAccount
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.port.AuditLogPort
import banghak.stock.core.port.CredentialMetaPort
import banghak.stock.core.port.DevicePort
import banghak.stock.core.port.InstallationPort
import banghak.stock.core.port.PasswordHasherPort
import banghak.stock.core.port.RecoveryCodePort
import banghak.stock.core.port.RegistrationCodePort
import banghak.stock.core.port.SessionPort
import banghak.stock.core.port.TokenGeneratorPort
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

    override fun findAll(): List<UserAccount> = accounts.values.toList()

    override fun findByDisplayName(displayName: String): UserAccount? =
        accounts.values.firstOrNull { it.displayName == displayName }

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

/** 고정 코드 하나만 맞는 TOTP. 등록은 대기 → 확인 뒤 활성. */
class FakeTotpPort(private val validCode: String = "123456") : TotpPort {
    val pending = mutableSetOf<UserId>()
    val active = mutableSetOf<UserId>()

    override fun enroll(userId: UserId, accountLabel: String): TotpEnrollment {
        pending += userId
        return TotpEnrollment(byteArrayOf(1, 2, 3), "FAKESEED")
    }

    override fun confirmEnrollment(userId: UserId, code: String, now: Instant): Long? {
        if (userId !in pending || code != validCode) return null
        pending -= userId
        active += userId
        return now.epochSecond / 30
    }

    override fun verify(userId: UserId, code: String, now: Instant): Long? =
        if (userId in active && code == validCode) now.epochSecond / 30 else null

    override fun remove(userId: UserId) {
        pending -= userId
        active -= userId
    }
}

class MemorySessionPort : SessionPort {
    val sessions = mutableMapOf<String, Session>()

    override fun save(session: Session) {
        sessions[session.sessionId] = session
    }

    override fun findById(sessionId: String): Session? = sessions[sessionId]

    override fun findActiveByUserId(userId: UserId): List<Session> =
        sessions.values.filter { it.userId == userId && it.revokedAt == null }
}

class MemoryDevicePort : DevicePort {
    val devices = mutableListOf<Device>()

    override fun findAll(): List<Device> = devices.toList()

    override fun save(device: Device) {
        devices.removeAll { it.deviceId == device.deviceId }
        devices += device
    }
}

class MemoryRecoveryCodePort : RecoveryCodePort {
    val codes = mutableMapOf<String, RecoveryCode>()

    override fun replaceAll(userId: UserId, codes: List<RecoveryCode>) {
        this.codes.values.removeAll { it.userId == userId }
        codes.forEach { this.codes[it.recoveryCodeId] = it }
    }

    override fun findUsableByUserId(userId: UserId): List<RecoveryCode> =
        codes.values.filter { it.userId == userId && it.isUsable }

    override fun save(code: RecoveryCode) {
        codes[code.recoveryCodeId] = code
    }
}

class MemoryRegistrationCodePort : RegistrationCodePort {
    val codes = mutableMapOf<String, RegistrationCode>()

    override fun save(code: RegistrationCode) {
        codes[code.registrationCodeId] = code
    }

    override fun findUnused(): List<RegistrationCode> = codes.values.filter { it.usedAt == null }
}

/** 예측 가능한 토큰. 해시는 접두어만 붙임. */
class FakeTokenGenerator : TokenGeneratorPort {
    private var counter = 0

    override fun newToken(): String = "token-" + (++counter)

    override fun newHumanCode(): String =
        "CODE" + (++counter).toString().padStart(2, '0') + "-ABCDE"

    override fun hash(token: String): String = "hash:$token"
}
