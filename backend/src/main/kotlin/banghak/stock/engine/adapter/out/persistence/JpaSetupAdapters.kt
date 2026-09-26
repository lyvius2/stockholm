package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.account.AuditEntry
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.CredentialMeta
import banghak.stock.core.domain.account.CredentialStatus
import banghak.stock.core.domain.account.Installation
import banghak.stock.core.domain.account.LlmPreset
import banghak.stock.core.domain.account.SetupState
import banghak.stock.core.domain.account.TossDecision
import banghak.stock.core.domain.account.UserAccount
import banghak.stock.core.domain.account.UserStatus
import banghak.stock.core.domain.identity.Role
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.port.AuditLogPort
import banghak.stock.core.port.CredentialMetaPort
import banghak.stock.core.port.InstallationPort
import banghak.stock.core.port.UserAccountPort
import banghak.stock.engine.adapter.out.persistence.entity.AppUserEntity
import banghak.stock.engine.adapter.out.persistence.entity.AuditLogEntity
import banghak.stock.engine.adapter.out.persistence.entity.CredentialMetaEntity
import banghak.stock.engine.adapter.out.persistence.entity.InstallationEntity
import banghak.stock.engine.adapter.out.persistence.repository.AppUserRepository
import banghak.stock.engine.adapter.out.persistence.repository.AuditLogRepository
import banghak.stock.engine.adapter.out.persistence.repository.CredentialMetaRepository
import banghak.stock.engine.adapter.out.persistence.repository.InstallationRepository
import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.crypto.UlidGenerator
import java.time.Clock
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaInstallationAdapter(private val repository: InstallationRepository) : InstallationPort {
    override fun load(): Installation? = repository.findAll().firstOrNull()?.toDomain()

    override fun save(installation: Installation) {
        val row =
            repository.findById(installation.installationId).orElse(null)
                ?: InstallationEntity(
                    installationId = installation.installationId,
                    setupState = installation.setupState.name,
                    adminUserId = null,
                    llmPreset = null,
                    lastPublicIp = null,
                    stockMasterSyncedAt = null,
                    lastLoginUserId = null,
                    createdAt = installation.createdAt,
                    updatedAt = installation.updatedAt,
                )
        row.setupState = installation.setupState.name
        row.adminUserId = installation.adminUserId?.value
        row.llmPreset = installation.llmPreset?.name
        row.lastLoginUserId = installation.lastLoginUserId?.value
        row.updatedAt = installation.updatedAt
        repository.save(row)
    }

    private fun InstallationEntity.toDomain() =
        Installation(
            installationId = installationId,
            setupState = SetupState.valueOf(setupState),
            adminUserId = adminUserId?.let(::UserId),
            llmPreset = llmPreset?.let(LlmPreset::valueOf),
            lastLoginUserId = lastLoginUserId?.let(::UserId),
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}

@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaUserAccountAdapter(private val repository: AppUserRepository) : UserAccountPort {
    override fun count(): Int = repository.count().toInt()

    override fun findById(userId: UserId): UserAccount? =
        repository.findById(userId.value).orElse(null)?.toDomain()

    override fun save(account: UserAccount) {
        val row =
            repository.findById(account.userId.value).orElse(null)
                ?: AppUserEntity(
                    userId = account.userId.value,
                    role = account.role.name,
                    displayName = account.displayName,
                    passwordHash = account.passwordHash,
                    totpEnrolledAt = null,
                    totpLastCounter = 0,
                    failedLogins = 0,
                    lockedUntil = null,
                    status = account.status.name,
                    tossKeyDecision = account.tossKeyDecision.name,
                    extraKeyWrap = false,
                    autoStopOnLogout = false,
                    email = null,
                    slackUserId = null,
                    createdAt = account.createdAt,
                    updatedAt = account.updatedAt,
                )
        row.role = account.role.name
        row.displayName = account.displayName
        row.passwordHash = account.passwordHash
        row.totpEnrolledAt = account.totpEnrolledAt
        row.totpLastCounter = account.totpLastCounter
        row.failedLogins = account.failedLogins
        row.lockedUntil = account.lockedUntil
        row.status = account.status.name
        row.tossKeyDecision = account.tossKeyDecision.name
        row.autoStopOnLogout = account.autoStopOnLogout
        row.email = account.email
        row.slackUserId = account.slackUserId
        row.updatedAt = account.updatedAt
        repository.save(row)
    }

    private fun AppUserEntity.toDomain() =
        UserAccount(
            userId = UserId(userId),
            role = Role.valueOf(role),
            displayName = displayName,
            passwordHash = passwordHash,
            totpEnrolledAt = totpEnrolledAt,
            totpLastCounter = totpLastCounter,
            failedLogins = failedLogins,
            lockedUntil = lockedUntil,
            status = UserStatus.valueOf(status),
            tossKeyDecision = TossDecision.valueOf(tossKeyDecision),
            autoStopOnLogout = autoStopOnLogout,
            email = email,
            slackUserId = slackUserId,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}

@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaCredentialMetaAdapter(
    private val repository: CredentialMetaRepository,
    private val ulids: UlidGenerator,
    private val clock: Clock,
) : CredentialMetaPort {
    private val json = JsonMapper.builder().build()

    override fun upsert(meta: CredentialMeta) {
        val now = clock.instant()
        val existing =
            if (meta.userId == null) repository.findSharedByKind(meta.kind.name)
            else repository.findByKindAndUserId(meta.kind.name, meta.userId.value)
        val row =
            existing
                ?: CredentialMetaEntity(
                    credentialId = ulids.next().value,
                    kind = meta.kind.name,
                    scope = meta.scope.name,
                    userId = meta.userId?.value,
                    last4 = null,
                    status = meta.status.name,
                    statusDetail = null,
                    verifiedAt = null,
                    responseMs = null,
                    extraJson = null,
                    createdAt = now,
                    updatedAt = now,
                )
        row.last4 = meta.last4
        row.status = meta.status.name
        row.statusDetail = meta.statusDetail
        row.verifiedAt = meta.verifiedAt
        row.extraJson = if (meta.detail.isEmpty()) null else json.writeValueAsString(meta.detail)
        row.updatedAt = now
        repository.save(row)
    }

    override fun findShared(): List<CredentialMeta> = repository.findShared().map { it.toDomain() }

    override fun findByUser(userId: UserId): List<CredentialMeta> =
        repository.findByUserId(userId.value).map { it.toDomain() }

    override fun delete(kind: CredentialKind, userId: UserId?) {
        val row =
            if (userId == null) repository.findSharedByKind(kind.name)
            else repository.findByKindAndUserId(kind.name, userId.value)
        row?.let(repository::delete)
    }

    private fun CredentialMetaEntity.toDomain() =
        CredentialMeta(
            kind = CredentialKind.valueOf(kind),
            userId = userId?.let(::UserId),
            status = CredentialStatus.valueOf(status),
            last4 = last4,
            verifiedAt = verifiedAt,
            statusDetail = statusDetail,
            detail =
                extraJson
                    ?.let {
                        json.readValue(it, Map::class.java).entries.associate { (k, v) ->
                            k.toString() to v.toString()
                        }
                    }
                    .orEmpty(),
        )
}

@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaAuditLogAdapter(
    private val repository: AuditLogRepository,
    private val ulids: UlidGenerator,
) : AuditLogPort {
    private val json = JsonMapper.builder().build()

    override fun record(entry: AuditEntry) {
        repository.save(
            AuditLogEntity(
                auditId = ulids.next().value,
                occurredAt = entry.occurredAt,
                userId = entry.userId?.value,
                deviceId = entry.deviceId?.value,
                action = entry.action.name,
                target = entry.target,
                result = entry.result,
                detailJson =
                    if (entry.detail.isEmpty()) null else json.writeValueAsString(entry.detail),
            )
        )
    }
}
