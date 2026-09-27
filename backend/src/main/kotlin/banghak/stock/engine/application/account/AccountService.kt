package banghak.stock.engine.application.account

import banghak.stock.core.domain.account.AuditAction
import banghak.stock.core.domain.account.AuditEntry
import banghak.stock.core.domain.account.PasswordPolicy
import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.account.RecoveryCode
import banghak.stock.core.domain.account.TotpEnrollment
import banghak.stock.core.domain.account.UserAccount
import banghak.stock.core.domain.account.UserSummary
import banghak.stock.core.domain.error.AuthenticationFailedException
import banghak.stock.core.domain.error.ForbiddenException
import banghak.stock.core.domain.error.TotpRejectedException
import banghak.stock.core.port.AuditLogPort
import banghak.stock.core.port.PasswordHasherPort
import banghak.stock.core.port.RecoveryCodePort
import banghak.stock.core.port.TokenGeneratorPort
import banghak.stock.core.port.TotpPort
import banghak.stock.core.port.UserAccountPort
import banghak.stock.core.usecase.AccountUseCase
import banghak.stock.core.usecase.ChangePasswordCommand
import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.crypto.UlidGenerator
import java.time.Clock
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 본인 계정 변경. 항목마다 요구하는 인증(현재 비밀번호·현재 TOTP·step-up)이 다름. */
@Service
@Profile(RuntimeProfiles.ENGINE)
@Transactional
class AccountService(
    private val users: UserAccountPort,
    private val recoveryCodes: RecoveryCodePort,
    private val passwordHasher: PasswordHasherPort,
    private val totp: TotpPort,
    private val tokens: TokenGeneratorPort,
    private val audit: AuditLogPort,
    private val ulids: UlidGenerator,
    private val clock: Clock,
) : AccountUseCase {
    @Transactional(readOnly = true)
    override fun me(principal: Principal): UserSummary =
        LoginService.summaryOf(accountOf(principal))

    override fun changePassword(principal: Principal, command: ChangePasswordCommand) {
        val account = accountOf(principal)
        if (!passwordHasher.matches(command.current, account.passwordHash))
            throw AuthenticationFailedException("현재 비밀번호가 맞지 않음")
        PasswordPolicy.validate(command.new, command.confirmation)
        val now = clock.instant()
        users.save(account.copy(passwordHash = passwordHasher.hash(command.new), updatedAt = now))
        audit.record(
            AuditEntry(
                now,
                account.userId,
                principal.session.deviceId,
                AuditAction.PASSWORD_CHANGED,
                null,
                "OK",
            )
        )
    }

    override fun startTotpReenrollment(principal: Principal, currentCode: String): TotpEnrollment {
        val account = accountOf(principal)
        val now = clock.instant()
        val counter = totp.verify(account.userId, currentCode, now)
        if (counter == null || counter <= account.totpLastCounter)
            throw TotpRejectedException("현재 TOTP 코드가 맞지 않음")
        users.save(account.copy(totpLastCounter = counter, updatedAt = now))
        return totp.enroll(account.userId, account.displayName)
    }

    override fun confirmTotpReenrollment(principal: Principal, newCode: String) {
        val account = accountOf(principal)
        val now = clock.instant()
        val counter =
            totp.confirmEnrollment(account.userId, newCode, now)
                ?: throw TotpRejectedException("새 TOTP 코드가 맞지 않음")
        users.save(account.copy(totpEnrolledAt = now, totpLastCounter = counter, updatedAt = now))
        audit.record(
            AuditEntry(
                now,
                account.userId,
                principal.session.deviceId,
                AuditAction.TOTP_REENROLLED,
                null,
                "OK",
            )
        )
    }

    override fun reissueRecoveryCodes(principal: Principal): List<String> {
        val now = clock.instant()
        if (!principal.session.hasFreshStepUp(now)) throw ForbiddenException("step-up 인증이 필요함")
        val account = accountOf(principal)
        val plain = List(RecoveryCode.COUNT) { tokens.newHumanCode() }
        recoveryCodes.replaceAll(
            account.userId,
            plain.map { code ->
                RecoveryCode(
                    ulids.next().value,
                    account.userId,
                    passwordHasher.hash(code.toCharArray()),
                    null,
                    now,
                )
            },
        )
        audit.record(
            AuditEntry(
                now,
                account.userId,
                principal.session.deviceId,
                AuditAction.RECOVERY_REISSUED,
                null,
                "OK",
            )
        )
        return plain
    }

    private fun accountOf(principal: Principal): UserAccount =
        users.findById(principal.userId) ?: throw AuthenticationFailedException("계정이 없음")
}
