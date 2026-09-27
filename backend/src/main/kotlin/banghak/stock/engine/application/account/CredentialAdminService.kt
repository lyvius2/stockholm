package banghak.stock.engine.application.account

import banghak.stock.core.domain.account.AuditAction
import banghak.stock.core.domain.account.AuditEntry
import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.CredentialMeta
import banghak.stock.core.domain.account.CredentialStatus
import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretScope
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.error.ForbiddenException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.port.AuditLogPort
import banghak.stock.core.port.CredentialMetaPort
import banghak.stock.core.port.CredentialRecheckPort
import banghak.stock.core.port.CredentialVerifier
import banghak.stock.core.port.SecretStorePort
import banghak.stock.core.usecase.CredentialAdminUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 키 교체·삭제·재검증. 값은 검증 요청이 끝나면 지우고 응답·로그에 싣지 않음. */
@Service
@Profile(RuntimeProfiles.ENGINE)
@Transactional
class CredentialAdminService(
    private val credentials: CredentialMetaPort,
    private val secrets: SecretStorePort,
    private val verifiers: List<CredentialVerifier>,
    private val recheck: CredentialRecheckPort,
    private val audit: AuditLogPort,
    private val clock: Clock,
) : CredentialAdminUseCase {
    private val verifierByKind = verifiers.associateBy { it.kind }

    @Transactional(readOnly = true)
    override fun sharedCredentials(admin: Principal): List<CredentialMeta> {
        requireAdmin(admin)
        return credentials.findShared()
    }

    override fun replaceShared(
        admin: Principal,
        kind: CredentialKind,
        fields: Map<String, SecretValue>,
    ): CredentialCheck {
        requireAdmin(admin)
        if (kind.scope != SecretScope.SHARED) throw InvalidValueException("$kind 는 공유 키가 아님")
        return verifyAndStore(admin, kind, null, fields)
    }

    override fun deleteShared(admin: Principal, kind: CredentialKind) {
        requireAdmin(admin)
        if (kind.scope != SecretScope.SHARED) throw InvalidValueException("$kind 는 공유 키가 아님")
        kind.fields.forEach { field -> secrets.delete(SecretKey.shared(kind.secretName(field))) }
        credentials.delete(kind, null)
        audit.record(
            AuditEntry(
                clock.instant(),
                admin.userId,
                admin.session.deviceId,
                AuditAction.KEY_DELETE,
                kind.name,
                "OK",
            )
        )
    }

    override fun recheckShared(admin: Principal, kind: CredentialKind): CredentialCheck {
        requireAdmin(admin)
        val now = clock.instant()
        val check = recheck.recheck(kind, null)
        val existing = credentials.findShared().firstOrNull { it.kind == kind }
        credentials.upsert(
            CredentialMeta(
                kind,
                null,
                statusOf(check),
                existing?.last4,
                if (check.isOk) now else existing?.verifiedAt,
                reasonOf(check),
                (check as? CredentialCheck.Ok)?.detail.orEmpty(),
            )
        )
        audit.record(
            AuditEntry(
                now,
                admin.userId,
                admin.session.deviceId,
                AuditAction.KEY_VERIFY,
                kind.name,
                statusOf(check).name,
            )
        )
        return check
    }

    override fun replaceOwnToss(
        principal: Principal,
        fields: Map<String, SecretValue>,
    ): CredentialCheck {
        if (!principal.session.hasFreshStepUp(clock.instant()))
            throw ForbiddenException("step-up 인증이 필요함")
        return verifyAndStore(principal, CredentialKind.TOSS, principal.userId, fields)
    }

    private fun verifyAndStore(
        actor: Principal,
        kind: CredentialKind,
        owner: UserId?,
        fields: Map<String, SecretValue>,
    ): CredentialCheck {
        val verifier = verifierByKind[kind] ?: throw InvalidValueException("$kind 검증기가 없음")
        val now = clock.instant()
        val check = verifier.verify(fields)
        if (check is CredentialCheck.Ok) {
            kind.fields.forEach { field ->
                val key =
                    if (owner == null) SecretKey.shared(kind.secretName(field))
                    else SecretKey.user(owner, kind.secretName(field))
                secrets.put(key, fields.getValue(field))
            }
            credentials.upsert(
                CredentialMeta(
                    kind,
                    owner,
                    CredentialStatus.VERIFIED,
                    fields[kind.fields.last()]?.last4,
                    now,
                    null,
                    check.detail,
                )
            )
            audit.record(
                AuditEntry(
                    now,
                    actor.userId,
                    actor.session.deviceId,
                    AuditAction.KEY_SET,
                    kind.name,
                    "OK",
                )
            )
        }
        audit.record(
            AuditEntry(
                now,
                actor.userId,
                actor.session.deviceId,
                AuditAction.KEY_VERIFY,
                kind.name,
                statusOf(check).name,
            )
        )
        fields.values.forEach { it.wipe() }
        return check
    }

    private fun requireAdmin(principal: Principal) {
        if (!principal.isAdmin) throw ForbiddenException("admin 만 할 수 있음")
        if (!principal.session.hasFreshStepUp(clock.instant()))
            throw ForbiddenException("step-up 인증이 필요함")
    }

    private fun statusOf(check: CredentialCheck) =
        when (check) {
            is CredentialCheck.Ok -> CredentialStatus.VERIFIED
            is CredentialCheck.Rejected -> CredentialStatus.REJECTED
            is CredentialCheck.Unreachable -> CredentialStatus.UNREACHABLE
        }

    private fun reasonOf(check: CredentialCheck) =
        (check as? CredentialCheck.Rejected)?.reason
            ?: (check as? CredentialCheck.Unreachable)?.reason
}
