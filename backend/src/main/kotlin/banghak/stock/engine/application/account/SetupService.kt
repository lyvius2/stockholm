package banghak.stock.engine.application.account

import banghak.stock.core.domain.account.AuditAction
import banghak.stock.core.domain.account.AuditEntry
import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.CredentialMeta
import banghak.stock.core.domain.account.CredentialStatus
import banghak.stock.core.domain.account.Installation
import banghak.stock.core.domain.account.LlmPreset
import banghak.stock.core.domain.account.PasswordPolicy
import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretScope
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.account.SessionKind
import banghak.stock.core.domain.account.SetupProgress
import banghak.stock.core.domain.account.SetupState
import banghak.stock.core.domain.account.TossDecision
import banghak.stock.core.domain.account.UserAccount
import banghak.stock.core.domain.account.UserStatus
import banghak.stock.core.domain.error.IllegalSetupTransitionException
import banghak.stock.core.domain.error.TooManyUsersException
import banghak.stock.core.domain.error.TotpRejectedException
import banghak.stock.core.domain.identity.Role
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.port.AuditLogPort
import banghak.stock.core.port.CredentialMetaPort
import banghak.stock.core.port.CredentialVerifier
import banghak.stock.core.port.FormatCredentialVerifier
import banghak.stock.core.port.InstallationPort
import banghak.stock.core.port.PasswordHasherPort
import banghak.stock.core.port.SecretStorePort
import banghak.stock.core.port.TotpPort
import banghak.stock.core.port.UserAccountPort
import banghak.stock.core.usecase.AdminCreated
import banghak.stock.core.usecase.CreateAdminCommand
import banghak.stock.core.usecase.LoginCommand
import banghak.stock.core.usecase.SetupWizardUseCase
import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.crypto.UlidGenerator
import java.time.Clock
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Profile(RuntimeProfiles.ENGINE)
@Transactional
class SetupService(
    private val installations: InstallationPort,
    private val users: UserAccountPort,
    private val credentials: CredentialMetaPort,
    private val secrets: SecretStorePort,
    private val verifiers: List<CredentialVerifier>,
    private val passwordHasher: PasswordHasherPort,
    private val totp: TotpPort,
    private val audit: AuditLogPort,
    private val ulids: UlidGenerator,
    private val clock: Clock,
    private val loginService: LoginService,
) : SetupWizardUseCase {
    private val verifierByKind = verifiers.associateBy { it.kind }

    // 실제 API 검증기가 아직 없는 종류는 형식만 검사함
    private fun verifierFor(kind: CredentialKind): CredentialVerifier =
        verifierByKind[kind] ?: FormatCredentialVerifier(kind)

    /** 읽기만 함. 설치 행이 없으면 저장하지 않고 NOT_STARTED 로 봄(저장은 쓰기 메서드가 함). */
    @Transactional(readOnly = true)
    override fun progress(): SetupProgress {
        val installation = installations.load() ?: newInstallation()
        val admin = installation.adminUserId?.let(users::findById)
        val shared = credentials.findShared()
        val personal = admin?.let { credentials.findByUser(it.userId) }.orEmpty()
        return SetupProgress(
            state = installation.setupState,
            adminDisplayName = admin?.displayName,
            credentials = shared + personal,
            tossDecision = admin?.tossKeyDecision ?: TossDecision.NONE,
            llmPreset = installation.llmPreset,
        )
    }

    override fun createAdmin(command: CreateAdminCommand): AdminCreated {
        val installation = installationOrNew()
        installation.setupState.requireExactly(SetupState.NOT_STARTED)
        if (users.count() >= UserAccount.MAX_USERS)
            throw TooManyUsersException("사용자는 최대 ${UserAccount.MAX_USERS}명")
        PasswordPolicy.validateDisplayName(command.displayName)
        PasswordPolicy.validate(command.password, command.passwordConfirmation)
        val now = clock.instant()
        val userId = UserId.from(ulids.next())
        val existingAdmin = installation.adminUserId?.let(users::findById)
        // TOTP 확인 전에 다시 부르면 이전 admin 행을 버리고 새로 만듦(상태는 아직 NOT_STARTED)
        val account =
            UserAccount(
                userId = existingAdmin?.userId ?: userId,
                role = Role.ADMIN,
                displayName = command.displayName,
                passwordHash = passwordHasher.hash(command.password),
                totpEnrolledAt = null,
                totpLastCounter = 0,
                failedLogins = 0,
                lockedUntil = null,
                status = UserStatus.ACTIVE,
                tossKeyDecision = TossDecision.NONE,
                autoStopOnLogout = false,
                email = null,
                slackUserId = null,
                createdAt = existingAdmin?.createdAt ?: now,
                updatedAt = now,
            )
        users.save(account)
        installations.save(installation.copy(adminUserId = account.userId, updatedAt = now))
        val enrollment = totp.enroll(account.userId, command.displayName)
        audit.record(
            AuditEntry(now, account.userId, null, AuditAction.SETUP_ADMIN_CREATED, null, "OK")
        )
        return AdminCreated(account.userId, enrollment)
    }

    override fun confirmAdminTotp(code: String): SetupProgress {
        val installation = installationOrNew()
        installation.setupState.requireExactly(SetupState.NOT_STARTED)
        val admin = adminOf(installation)
        val now = clock.instant()
        val counter =
            totp.confirmEnrollment(admin.userId, code, now)
                ?: throw TotpRejectedException("TOTP 코드가 맞지 않음")
        users.save(admin.copy(totpEnrolledAt = now, totpLastCounter = counter, updatedAt = now))
        installations.save(installation.advance(SetupState.ADMIN_CREATED, now))
        audit.record(
            AuditEntry(now, admin.userId, null, AuditAction.SETUP_TOTP_CONFIRMED, null, "OK")
        )
        return progress()
    }

    override fun issueWizardSession(): String {
        val installation = installationOrNew()
        installation.setupState.requireAtLeast(SetupState.ADMIN_CREATED)
        return loginService
            .issueSession(adminOf(installation), SessionKind.SETUP, clock.instant())
            .token
    }

    override fun reopenWizardSession(password: CharArray, totpCode: String): String {
        val installation = installationOrNew()
        installation.setupState.requireAtLeast(SetupState.ADMIN_CREATED)
        requireNotComplete(installation)
        val admin = adminOf(installation)
        return loginService.login(LoginCommand(admin.userId, password, totpCode, null)).token
    }

    override fun registerSharedCredential(
        kind: CredentialKind,
        fields: Map<String, SecretValue>,
    ): CredentialCheck {
        if (kind.scope != SecretScope.SHARED)
            throw IllegalSetupTransitionException("$kind 는 공유 키가 아님")
        val installation = installationOrNew()
        installation.setupState.requireAtLeast(SetupState.ADMIN_CREATED)
        requireNotComplete(installation)
        return verifyAndStore(kind, null, fields, adminOf(installation).userId)
    }

    override fun finishSharedKeys(preset: LlmPreset): SetupProgress {
        val installation = installationOrNew()
        installation.setupState.requireExactly(SetupState.ADMIN_CREATED)
        val current = progress()
        if (!current.canFinishSharedKeys)
            throw IllegalSetupTransitionException("LLM 1개 이상과 DART 키가 검증되어야 함")
        val now = clock.instant()
        installations.save(
            installation.advance(SetupState.SHARED_KEYS_DONE, now).copy(llmPreset = preset)
        )
        audit.record(
            AuditEntry(
                now,
                installation.adminUserId,
                null,
                AuditAction.SETUP_KEYS_DONE,
                preset.name,
                "OK",
            )
        )
        return progress()
    }

    override fun registerTossCredential(fields: Map<String, SecretValue>): CredentialCheck {
        val installation = installationOrNew()
        installation.setupState.requireAtLeast(SetupState.SHARED_KEYS_DONE)
        requireNotComplete(installation)
        val admin = adminOf(installation)
        return verifyAndStore(CredentialKind.TOSS, admin.userId, fields, admin.userId)
    }

    override fun decideToss(decision: TossDecision): SetupProgress {
        val installation = installationOrNew()
        installation.setupState.requireExactly(SetupState.SHARED_KEYS_DONE)
        if (decision == TossDecision.NONE)
            throw IllegalSetupTransitionException("등록 또는 나중에 중 하나를 골라야 함")
        if (decision == TossDecision.REGISTERED && !progress().hasVerifiedToss)
            throw IllegalSetupTransitionException("토스 키가 검증되어야 등록으로 진행할 수 있음")
        val admin = adminOf(installation)
        val now = clock.instant()
        users.save(admin.copy(tossKeyDecision = decision, updatedAt = now))
        installations.save(installation.advance(SetupState.TOSS_DECIDED, now))
        audit.record(
            AuditEntry(now, admin.userId, null, AuditAction.SETUP_TOSS_DECIDED, decision.name, "OK")
        )
        return progress()
    }

    override fun complete(): SetupProgress {
        val installation = installationOrNew()
        installation.setupState.requireExactly(SetupState.TOSS_DECIDED)
        val now = clock.instant()
        installations.save(installation.advance(SetupState.COMPLETE, now))
        audit.record(
            AuditEntry(now, installation.adminUserId, null, AuditAction.SETUP_COMPLETED, null, "OK")
        )
        return progress()
    }

    private fun verifyAndStore(
        kind: CredentialKind,
        owner: UserId?,
        fields: Map<String, SecretValue>,
        actor: UserId,
    ): CredentialCheck {
        val verifier = verifierFor(kind)
        val now = clock.instant()
        val check = verifier.verify(fields)
        val status =
            when (check) {
                is CredentialCheck.Ok -> CredentialStatus.VERIFIED
                is CredentialCheck.Rejected -> CredentialStatus.REJECTED
                is CredentialCheck.Unreachable -> CredentialStatus.UNREACHABLE
            }
        val statusDetail =
            (check as? CredentialCheck.Rejected)?.reason
                ?: (check as? CredentialCheck.Unreachable)?.reason
        val last4 = fields[kind.fields.last()]?.last4
        if (check is CredentialCheck.Ok) {
            kind.fields.forEach { field ->
                secrets.put(secretKeyOf(kind, field, owner), fields.getValue(field))
            }
            credentials.upsert(CredentialMeta(kind, owner, status, last4, now, null, check.detail))
            audit.record(AuditEntry(now, actor, null, AuditAction.KEY_SET, kind.name, "OK"))
        } else {
            credentials.upsert(CredentialMeta(kind, owner, status, null, null, statusDetail))
        }
        audit.record(AuditEntry(now, actor, null, AuditAction.KEY_VERIFY, kind.name, status.name))
        fields.values.forEach { it.wipe() }
        return check
    }

    private fun secretKeyOf(kind: CredentialKind, field: String, owner: UserId?): SecretKey =
        if (owner == null) SecretKey.shared(kind.secretName(field))
        else SecretKey.user(owner, kind.secretName(field))

    private fun requireNotComplete(installation: Installation) {
        if (installation.setupState.isComplete)
            throw IllegalSetupTransitionException("마법사가 이미 끝남. 키 변경은 설정 화면에서 함")
    }

    private fun adminOf(installation: Installation): UserAccount =
        installation.adminUserId?.let(users::findById)
            ?: throw IllegalSetupTransitionException("admin 계정이 아직 없음")

    private fun installationOrNew(): Installation =
        installations.load() ?: newInstallation().also(installations::save)

    private fun newInstallation(): Installation =
        Installation(
            installationId = ulids.next().value,
            setupState = SetupState.NOT_STARTED,
            adminUserId = null,
            llmPreset = null,
            lastLoginUserId = null,
            createdAt = clock.instant(),
            updatedAt = clock.instant(),
        )
}
