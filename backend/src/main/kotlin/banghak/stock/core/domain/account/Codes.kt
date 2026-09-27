package banghak.stock.core.domain.account

import banghak.stock.core.domain.identity.UserId
import java.time.Duration
import java.time.Instant

/** 복구 코드 한 장. 해시만 저장하고 1회용. */
data class RecoveryCode(
    val recoveryCodeId: String,
    val userId: UserId,
    val codeHash: String,
    val usedAt: Instant?,
    val createdAt: Instant,
) {
    val isUsable: Boolean
        get() = usedAt == null

    companion object {
        const val COUNT = 8
    }
}

/** 구성원 등록 코드. admin 이 발급, 24시간, 1회용, 해시만 저장. */
data class RegistrationCode(
    val registrationCodeId: String,
    val codeHash: String,
    val issuedBy: UserId,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant?,
    val usedByUserId: UserId?,
) {
    fun isUsable(now: Instant): Boolean = usedAt == null && now.isBefore(expiresAt)

    companion object {
        val VALIDITY: Duration = Duration.ofHours(24)
    }
}

/** 이 설치(디바이스). 개인키는 Keychain, 공개키는 표. */
data class Device(
    val deviceId: banghak.stock.core.domain.identity.DeviceId,
    val publicKey: String,
    val name: String,
    val approvedByDeviceId: banghak.stock.core.domain.identity.DeviceId?,
    val registeredAt: Instant,
    val revokedAt: Instant?,
)
