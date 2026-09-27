package banghak.stock.engine.application.account

import banghak.stock.core.domain.account.AuditAction
import banghak.stock.core.domain.account.AuditEntry
import banghak.stock.core.domain.account.LoginPolicy
import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.account.Session
import banghak.stock.core.domain.account.SessionKind
import banghak.stock.core.domain.account.UserAccount
import banghak.stock.core.domain.account.UserStatus
import banghak.stock.core.domain.account.UserSummary
import banghak.stock.core.domain.error.AuthenticationFailedException
import banghak.stock.core.domain.error.TotpRejectedException
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.port.AuditLogPort
import banghak.stock.core.port.DevicePort
import banghak.stock.core.port.PasswordHasherPort
import banghak.stock.core.port.RecoveryCodePort
import banghak.stock.core.port.SessionPort
import banghak.stock.core.port.TokenGeneratorPort
import banghak.stock.core.port.TotpPort
import banghak.stock.core.port.UserAccountPort
import banghak.stock.core.usecase.LoginCommand
import banghak.stock.core.usecase.LoginUseCase
import banghak.stock.core.usecase.SessionIssued
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import java.time.Instant
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 로그인·세션·step-up. 실패 사유는 응답에 밝히지 않고 감사 로그에만 남김. */
@Service
@Profile(RuntimeProfiles.ENGINE)
@Transactional
class LoginService(
    private val users: UserAccountPort,
    private val sessions: SessionPort,
    private val devices: DevicePort,
    private val recoveryCodes: RecoveryCodePort,
    private val passwordHasher: PasswordHasherPort,
    private val totp: TotpPort,
    private val tokens: TokenGeneratorPort,
    private val audit: AuditLogPort,
    private val clock: Clock,
) : LoginUseCase {
    @Transactional(readOnly = true)
    override fun users(): List<UserSummary> = users.findAll().map(::summaryOf)

    override fun login(command: LoginCommand): SessionIssued {
        val now = clock.instant()
        val account =
            users.findById(command.userId) ?: throw AuthenticationFailedException(GENERIC_FAILURE)
        if (
            account.status != UserStatus.ACTIVE ||
                LoginPolicy.isLocked(account, now) ||
                !account.isTotpEnrolled
        ) {
            audit.record(
                AuditEntry(now, account.userId, null, AuditAction.LOGIN_FAIL, null, "BLOCKED")
            )
            throw AuthenticationFailedException(GENERIC_FAILURE)
        }
        val passwordOk = passwordHasher.matches(command.password, account.passwordHash)
        val secondFactor = if (passwordOk) secondFactor(account, command, now) else null
        if (!passwordOk || secondFactor == null) {
            recordFailure(account, now)
            throw AuthenticationFailedException(GENERIC_FAILURE)
        }
        val updated = LoginPolicy.afterSuccess(account, now).let { secondFactor.apply(it) }
        users.save(updated)
        val issued = issueSession(updated, SessionKind.NORMAL, now)
        audit.record(
            AuditEntry(
                now,
                account.userId,
                issued.session.deviceId,
                AuditAction.LOGIN_OK,
                null,
                secondFactor.name,
            )
        )
        return issued
    }

    @Transactional(readOnly = true)
    override fun authenticate(token: String): Principal? {
        val session = sessions.findById(tokens.hash(token))
        if (session == null) return null
        if (!session.isActive(clock.instant())) return null
        val account = users.findById(session.userId) ?: return null
        if (account.status != UserStatus.ACTIVE) return null
        return Principal(account.userId, account.role, session)
    }

    override fun logout(principal: Principal) {
        val now = clock.instant()
        sessions.save(principal.session.copy(revokedAt = now))
        audit.record(
            AuditEntry(
                now,
                principal.userId,
                principal.session.deviceId,
                AuditAction.LOGOUT,
                null,
                "OK",
            )
        )
    }

    override fun stepUp(principal: Principal, totpCode: String): Session {
        val now = clock.instant()
        val account =
            users.findById(principal.userId) ?: throw AuthenticationFailedException(GENERIC_FAILURE)
        val counter = totp.verify(account.userId, totpCode, now)
        if (counter == null || counter <= account.totpLastCounter) {
            recordFailure(account, now)
            throw TotpRejectedException("TOTP 코드가 맞지 않음")
        }
        users.save(account.copy(totpLastCounter = counter, updatedAt = now))
        val refreshed = principal.session.copy(lastStepUpAt = now)
        sessions.save(refreshed)
        audit.record(
            AuditEntry(
                now,
                principal.userId,
                principal.session.deviceId,
                AuditAction.STEP_UP,
                null,
                "OK",
            )
        )
        return refreshed
    }

    /** 마법사 ① 뒤와 구성원 첫 TOTP 확인 뒤에 쓰는 세션 발급. 다른 서비스가 부름. */
    fun issueSession(account: UserAccount, kind: SessionKind, now: Instant): SessionIssued {
        val token = tokens.newToken()
        val ttl = if (kind == SessionKind.SETUP) Session.SETUP_TTL else Session.NORMAL_TTL
        val session =
            Session(
                tokens.hash(token),
                account.userId,
                localDeviceId(),
                kind,
                now,
                now.plus(ttl),
                null,
                null,
            )
        sessions.save(session)
        return SessionIssued(token, session, summaryOf(account))
    }

    private fun secondFactor(
        account: UserAccount,
        command: LoginCommand,
        now: Instant,
    ): SecondFactor? {
        command.totpCode?.let { code ->
            val counter = totp.verify(account.userId, code, now) ?: return null
            if (counter <= account.totpLastCounter) return null
            return SecondFactor("TOTP") { it.copy(totpLastCounter = counter) }
        }
        command.recoveryCode?.let { code ->
            val match =
                recoveryCodes.findUsableByUserId(account.userId).firstOrNull {
                    passwordHasher.matches(code.toCharArray(), it.codeHash)
                } ?: return null
            recoveryCodes.save(match.copy(usedAt = now))
            audit.record(
                AuditEntry(now, account.userId, null, AuditAction.RECOVERY_USED, null, "OK")
            )
            return SecondFactor("RECOVERY_CODE") { it }
        }
        return null
    }

    private fun recordFailure(account: UserAccount, now: Instant) {
        val updated = LoginPolicy.afterFailure(account, now)
        users.save(updated)
        val locked = updated.lockedUntil != null && updated.lockedUntil != account.lockedUntil
        audit.record(
            AuditEntry(
                now,
                account.userId,
                null,
                if (locked) AuditAction.LOCKED else AuditAction.LOGIN_FAIL,
                null,
                "FAIL",
            )
        )
    }

    private fun localDeviceId(): DeviceId =
        devices.findAll().firstOrNull { it.revokedAt == null }?.deviceId
            ?: throw IllegalStateException("이 설치의 디바이스가 등록되지 않음")

    private class SecondFactor(val name: String, val apply: (UserAccount) -> UserAccount)

    companion object {
        private const val GENERIC_FAILURE = "로그인할 수 없음"

        fun summaryOf(account: UserAccount) =
            UserSummary(
                userId = account.userId,
                displayName = account.displayName,
                role = account.role,
                status = account.status,
                isTotpEnrolled = account.isTotpEnrolled,
                tossKeyDecision = account.tossKeyDecision,
                lockedUntil = account.lockedUntil,
                createdAt = account.createdAt,
            )
    }
}
