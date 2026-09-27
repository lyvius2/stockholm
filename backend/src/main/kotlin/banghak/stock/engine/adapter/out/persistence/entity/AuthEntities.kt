package banghak.stock.engine.adapter.out.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "user_session")
class UserSessionEntity(
    @Id @Column(name = "session_id") val sessionId: String,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(name = "device_id", nullable = false) val deviceId: String,
    @Column(nullable = false) val kind: String,
    @Column(name = "issued_at", nullable = false) val issuedAt: Instant,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "last_step_up_at") var lastStepUpAt: Instant?,
    @Column(name = "revoked_at") var revokedAt: Instant?,
)

@Entity
@Table(name = "recovery_code")
class RecoveryCodeEntity(
    @Id @Column(name = "recovery_code_id") val recoveryCodeId: String,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(name = "code_hash", nullable = false) val codeHash: String,
    @Column(name = "used_at") var usedAt: Instant?,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
)

@Entity
@Table(name = "registration_code")
class RegistrationCodeEntity(
    @Id @Column(name = "registration_code_id") val registrationCodeId: String,
    @Column(name = "code_hash", nullable = false) val codeHash: String,
    @Column(name = "issued_by", nullable = false) val issuedBy: String,
    @Column(name = "issued_at", nullable = false) val issuedAt: Instant,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "used_at") var usedAt: Instant?,
    @Column(name = "used_by_user_id") var usedByUserId: String?,
)

@Entity
@Table(name = "device")
class DeviceEntity(
    @Id @Column(name = "device_id") val deviceId: String,
    @Column(name = "public_key", nullable = false) val publicKey: String,
    @Column(nullable = false) var name: String,
    @Column(name = "approved_by_device_id") val approvedByDeviceId: String?,
    @Column(name = "registered_at", nullable = false) val registeredAt: Instant,
    @Column(name = "revoked_at") var revokedAt: Instant?,
)
