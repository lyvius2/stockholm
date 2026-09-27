package banghak.stock.engine.application.account

import banghak.stock.core.domain.account.Device
import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.account.Session
import banghak.stock.core.domain.account.SessionKind
import banghak.stock.core.domain.account.TossDecision
import banghak.stock.core.domain.account.UserAccount
import banghak.stock.core.domain.account.UserStatus
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.Role
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.shared.crypto.UlidGenerator
import banghak.stock.support.fakes.FakePasswordHasher
import banghak.stock.support.fakes.FakeTokenGenerator
import banghak.stock.support.fakes.FakeTotpPort
import banghak.stock.support.fakes.MemoryAuditLogPort
import banghak.stock.support.fakes.MemoryCredentialMetaPort
import banghak.stock.support.fakes.MemoryDevicePort
import banghak.stock.support.fakes.MemoryRecoveryCodePort
import banghak.stock.support.fakes.MemoryRegistrationCodePort
import banghak.stock.support.fakes.MemorySecretStore
import banghak.stock.support.fakes.MemorySessionPort
import banghak.stock.support.fakes.MemoryUserAccountPort
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** 인증 서비스 테스트가 공유하는 fake 묶음과 표본 계정. 시계는 고정이고 [tick] 으로만 흐름. */
class AuthTestFixture {
    var now: Instant = Instant.parse("2026-09-27T09:00:00Z")
        private set

    val clock: Clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId) = this

            override fun instant() = now
        }

    val users = MemoryUserAccountPort()
    val sessions = MemorySessionPort()
    val devices = MemoryDevicePort()
    val recoveryCodes = MemoryRecoveryCodePort()
    val registrationCodes = MemoryRegistrationCodePort()
    val credentials = MemoryCredentialMetaPort()
    val secrets = MemorySecretStore()
    val hasher = FakePasswordHasher()
    val totp = FakeTotpPort()
    val tokens = FakeTokenGenerator()
    val audit = MemoryAuditLogPort()
    val ulids = UlidGenerator(clock)
    val deviceId = DeviceId.from(Ulid.of(now, ByteArray(10) { 9 }))

    val login =
        LoginService(users, sessions, devices, recoveryCodes, hasher, totp, tokens, audit, clock)
    val account = AccountService(users, recoveryCodes, hasher, totp, tokens, audit, ulids, clock)

    init {
        devices.save(Device(deviceId, "pk", "mac", null, now, null))
    }

    fun tick(seconds: Long) {
        now = now.plusSeconds(seconds)
    }

    fun user(
        name: String,
        role: Role = Role.MEMBER,
        password: String = "correct-horse-battery",
        totpEnrolled: Boolean = true,
    ): UserAccount {
        val id = UserId.from(Ulid.of(now, ByteArray(10) { name.hashCode().toByte() }))
        val account =
            UserAccount(
                id,
                role,
                name,
                hasher.hash(password.toCharArray()),
                if (totpEnrolled) now else null,
                0,
                0,
                null,
                UserStatus.ACTIVE,
                TossDecision.NONE,
                false,
                null,
                null,
                now,
                now,
            )
        users.save(account)
        if (totpEnrolled) totp.active += id
        return account
    }

    fun principal(account: UserAccount, steppedUp: Boolean = false): Principal {
        val session =
            Session(
                "hash:s-${account.displayName}",
                account.userId,
                deviceId,
                SessionKind.NORMAL,
                now,
                now.plus(Session.NORMAL_TTL),
                if (steppedUp) now else null,
                null,
            )
        sessions.save(session)
        return Principal(account.userId, account.role, session)
    }
}
