package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.account.Device
import banghak.stock.core.domain.account.RecoveryCode
import banghak.stock.core.domain.account.RegistrationCode
import banghak.stock.core.domain.account.Session
import banghak.stock.core.domain.account.SessionKind
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.port.DevicePort
import banghak.stock.core.port.RecoveryCodePort
import banghak.stock.core.port.RegistrationCodePort
import banghak.stock.core.port.SessionPort
import banghak.stock.engine.adapter.out.persistence.entity.DeviceEntity
import banghak.stock.engine.adapter.out.persistence.entity.RecoveryCodeEntity
import banghak.stock.engine.adapter.out.persistence.entity.RegistrationCodeEntity
import banghak.stock.engine.adapter.out.persistence.entity.UserSessionEntity
import banghak.stock.engine.adapter.out.persistence.repository.DeviceRepository
import banghak.stock.engine.adapter.out.persistence.repository.RecoveryCodeRepository
import banghak.stock.engine.adapter.out.persistence.repository.RegistrationCodeRepository
import banghak.stock.engine.adapter.out.persistence.repository.UserSessionRepository
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaSessionAdapter(private val repository: UserSessionRepository) : SessionPort {
    override fun save(session: Session) {
        val row =
            repository.findById(session.sessionId).orElse(null)
                ?: UserSessionEntity(
                    session.sessionId,
                    session.userId.value,
                    session.deviceId.value,
                    session.kind.name,
                    session.issuedAt,
                    session.expiresAt,
                    null,
                    null,
                )
        row.lastStepUpAt = session.lastStepUpAt
        row.revokedAt = session.revokedAt
        repository.save(row)
    }

    override fun findById(sessionId: String): Session? =
        repository.findById(sessionId).orElse(null)?.toDomain()

    override fun findActiveByUserId(userId: UserId): List<Session> =
        repository.findOpenByUserId(userId.value).map { it.toDomain() }

    private fun UserSessionEntity.toDomain() =
        Session(
            sessionId,
            UserId(userId),
            DeviceId(deviceId),
            SessionKind.valueOf(kind),
            issuedAt,
            expiresAt,
            lastStepUpAt,
            revokedAt,
        )
}

@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaRecoveryCodeAdapter(private val repository: RecoveryCodeRepository) : RecoveryCodePort {
    override fun replaceAll(userId: UserId, codes: List<RecoveryCode>) {
        repository.deleteAllByUserId(userId.value)
        repository.saveAll(
            codes.map {
                RecoveryCodeEntity(
                    it.recoveryCodeId,
                    it.userId.value,
                    it.codeHash,
                    it.usedAt,
                    it.createdAt,
                )
            }
        )
    }

    override fun findUsableByUserId(userId: UserId): List<RecoveryCode> =
        repository.findUsableByUserId(userId.value).map {
            RecoveryCode(it.recoveryCodeId, UserId(it.userId), it.codeHash, it.usedAt, it.createdAt)
        }

    override fun save(code: RecoveryCode) {
        val row =
            repository.findById(code.recoveryCodeId).orElse(null)
                ?: RecoveryCodeEntity(
                    code.recoveryCodeId,
                    code.userId.value,
                    code.codeHash,
                    null,
                    code.createdAt,
                )
        row.usedAt = code.usedAt
        repository.save(row)
    }
}

@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaRegistrationCodeAdapter(private val repository: RegistrationCodeRepository) :
    RegistrationCodePort {
    override fun save(code: RegistrationCode) {
        val row =
            repository.findById(code.registrationCodeId).orElse(null)
                ?: RegistrationCodeEntity(
                    code.registrationCodeId,
                    code.codeHash,
                    code.issuedBy.value,
                    code.issuedAt,
                    code.expiresAt,
                    null,
                    null,
                )
        row.usedAt = code.usedAt
        row.usedByUserId = code.usedByUserId?.value
        repository.save(row)
    }

    override fun findUnused(): List<RegistrationCode> =
        repository.findUnused().map {
            RegistrationCode(
                it.registrationCodeId,
                it.codeHash,
                UserId(it.issuedBy),
                it.issuedAt,
                it.expiresAt,
                it.usedAt,
                it.usedByUserId?.let(::UserId),
            )
        }
}

@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaDeviceAdapter(private val repository: DeviceRepository) : DevicePort {
    override fun findAll(): List<Device> =
        repository.findAll().map {
            Device(
                DeviceId(it.deviceId),
                it.publicKey,
                it.name,
                it.approvedByDeviceId?.let(::DeviceId),
                it.registeredAt,
                it.revokedAt,
            )
        }

    override fun save(device: Device) {
        val row =
            repository.findById(device.deviceId.value).orElse(null)
                ?: DeviceEntity(
                    device.deviceId.value,
                    device.publicKey,
                    device.name,
                    device.approvedByDeviceId?.value,
                    device.registeredAt,
                    null,
                )
        row.name = device.name
        row.revokedAt = device.revokedAt
        repository.save(row)
    }
}
