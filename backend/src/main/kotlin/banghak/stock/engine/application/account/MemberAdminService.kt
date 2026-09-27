package banghak.stock.engine.application.account

import banghak.stock.core.domain.account.AuditAction
import banghak.stock.core.domain.account.AuditEntry
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.PasswordPolicy
import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.account.RegistrationCode
import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.TossDecision
import banghak.stock.core.domain.account.UserAccount
import banghak.stock.core.domain.account.UserStatus
import banghak.stock.core.domain.account.UserSummary
import banghak.stock.core.domain.error.ForbiddenException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.RegistrationCodeInvalidException
import banghak.stock.core.domain.error.TooManyUsersException
import banghak.stock.core.domain.error.TotpRejectedException
import banghak.stock.core.domain.identity.Role
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.port.AuditLogPort
import banghak.stock.core.port.CredentialMetaPort
import banghak.stock.core.port.PasswordHasherPort
import banghak.stock.core.port.RegistrationCodePort
import banghak.stock.core.port.SecretStorePort
import banghak.stock.core.port.TokenGeneratorPort
import banghak.stock.core.port.TotpPort
import banghak.stock.core.port.UserAccountPort
import banghak.stock.core.usecase.MemberAdminUseCase
import banghak.stock.core.usecase.MemberRegistered
import banghak.stock.core.usecase.RegisterMemberCommand
import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.crypto.UlidGenerator
import java.time.Clock
import java.time.Instant
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 회원 관리. 모든 admin 동작은 admin 역할 + 5분 안 step-up 을 요구하고 감사 로그를 남김. */
@Service
@Profile(RuntimeProfiles.ENGINE)
@Transactional
class MemberAdminService(
    private val users: UserAccountPort,
    private val registrationCodes: RegistrationCodePort,
    private val credentials: CredentialMetaPort,
    private val secrets: SecretStorePort,
    private val passwordHasher: PasswordHasherPort,
    private val totp: TotpPort,
    private val tokens: TokenGeneratorPort,
    private val audit: AuditLogPort,
    private val ulids: UlidGenerator,
    private val clock: Clock,
) : MemberAdminUseCase {
    @Transactional(readOnly = true)
    override fun members(admin: Principal): List<UserSummary> {
        requireAdmin(admin)
        return users.findAll().map(LoginService::summaryOf)
    }

    override fun issueRegistrationCode(admin: Principal): String {
        requireAdmin(admin)
        val now = clock.instant()
        val pending = registrationCodes.findUnused().count { it.isUsable(now) }
        if (users.count() + pending >= UserAccount.MAX_USERS)
            throw TooManyUsersException("남은 자리가 없음(최대 ${UserAccount.MAX_USERS}명)")
        val code = tokens.newHumanCode()
        registrationCodes.save(
            RegistrationCode(
                ulids.next().value,
                passwordHasher.hash(code.toCharArray()),
                admin.userId,
                now,
                now.plus(RegistrationCode.VALIDITY),
                null,
                null,
            )
        )
        audit.record(
            AuditEntry(
                now,
                admin.userId,
                admin.session.deviceId,
                AuditAction.REGISTRATION_CODE_ISSUED,
                null,
                "OK",
            )
        )
        return code
    }

    override fun suspend(admin: Principal, target: UserId) =
        changeStatus(admin, target, UserStatus.SUSPENDED, AuditAction.MEMBER_SUSPENDED)

    override fun resume(admin: Principal, target: UserId) =
        changeStatus(admin, target, UserStatus.ACTIVE, AuditAction.MEMBER_RESUMED)

    override fun deleteTossCredential(admin: Principal, target: UserId) {
        requireAdmin(admin)
        val account = users.findById(target) ?: throw InvalidValueException("사용자가 없음")
        val now = clock.instant()
        CredentialKind.TOSS.fields.forEach { field ->
            secrets.delete(SecretKey.user(target, CredentialKind.TOSS.secretName(field)))
        }
        credentials.delete(CredentialKind.TOSS, target)
        users.save(account.copy(tossKeyDecision = TossDecision.NONE, updatedAt = now))
        audit.record(
            AuditEntry(
                now,
                admin.userId,
                admin.session.deviceId,
                AuditAction.KEY_DELETE,
                "TOSS:$target",
                "OK",
            )
        )
    }

    override fun transferAdmin(admin: Principal, target: UserId) {
        requireAdmin(admin)
        val current = users.findById(admin.userId) ?: throw InvalidValueException("admin 계정이 없음")
        val next = users.findById(target) ?: throw InvalidValueException("사용자가 없음")
        if (next.status != UserStatus.ACTIVE || next.userId == current.userId)
            throw InvalidValueException("활성 상태의 다른 사용자에게만 넘길 수 있음")
        val now = clock.instant()
        users.save(next.copy(role = Role.ADMIN, updatedAt = now))
        users.save(current.copy(role = Role.MEMBER, updatedAt = now))
        audit.record(
            AuditEntry(
                now,
                admin.userId,
                admin.session.deviceId,
                AuditAction.ADMIN_TRANSFERRED,
                target.value,
                "OK",
            )
        )
    }

    override fun register(command: RegisterMemberCommand): MemberRegistered {
        val now = clock.instant()
        val code =
            registrationCodes
                .findUnused()
                .filter { it.isUsable(now) }
                .firstOrNull {
                    passwordHasher.matches(command.registrationCode.toCharArray(), it.codeHash)
                } ?: throw RegistrationCodeInvalidException("등록 코드가 없거나 만료됨")
        if (users.count() >= UserAccount.MAX_USERS)
            throw TooManyUsersException("사용자는 최대 ${UserAccount.MAX_USERS}명")
        PasswordPolicy.validateDisplayName(command.displayName)
        if (users.findByDisplayName(command.displayName) != null)
            throw InvalidValueException("이미 있는 표시 이름")
        PasswordPolicy.validate(command.password, command.passwordConfirmation)
        val userId = UserId.from(ulids.next())
        users.save(
            newMember(userId, command.displayName, passwordHasher.hash(command.password), now)
        )
        registrationCodes.save(code.copy(usedAt = now, usedByUserId = userId))
        val enrollment = totp.enroll(userId, command.displayName)
        audit.record(AuditEntry(now, userId, null, AuditAction.MEMBER_REGISTERED, null, "OK"))
        return MemberRegistered(userId, enrollment)
    }

    override fun confirmMemberTotp(userId: UserId, code: String) {
        val account = users.findById(userId) ?: throw InvalidValueException("사용자가 없음")
        if (account.isTotpEnrolled) throw InvalidValueException("이미 등록됨")
        val now = clock.instant()
        val counter =
            totp.confirmEnrollment(userId, code, now)
                ?: throw TotpRejectedException("TOTP 코드가 맞지 않음")
        users.save(account.copy(totpEnrolledAt = now, totpLastCounter = counter, updatedAt = now))
    }

    private fun changeStatus(
        admin: Principal,
        target: UserId,
        status: UserStatus,
        action: AuditAction,
    ) {
        requireAdmin(admin)
        if (target == admin.userId) throw ForbiddenException("자기 자신은 정지·해제할 수 없음")
        val account = users.findById(target) ?: throw InvalidValueException("사용자가 없음")
        val now = clock.instant()
        users.save(account.copy(status = status, updatedAt = now))
        audit.record(
            AuditEntry(now, admin.userId, admin.session.deviceId, action, target.value, "OK")
        )
    }

    private fun requireAdmin(principal: Principal) {
        if (!principal.isAdmin) throw ForbiddenException("admin 만 할 수 있음")
        if (!principal.session.hasFreshStepUp(clock.instant()))
            throw ForbiddenException("step-up 인증이 필요함")
    }

    private fun newMember(userId: UserId, displayName: String, passwordHash: String, now: Instant) =
        UserAccount(
            userId,
            Role.MEMBER,
            displayName,
            passwordHash,
            null,
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
}
