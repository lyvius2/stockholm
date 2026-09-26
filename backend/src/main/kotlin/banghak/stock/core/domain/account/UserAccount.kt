package banghak.stock.core.domain.account

import banghak.stock.core.domain.identity.Role
import banghak.stock.core.domain.identity.UserId
import java.time.Instant

enum class UserStatus {
    ACTIVE,
    SUSPENDED,
}

/** 설치의 사용자 한 명. 비밀번호는 해시만, TOTP 시드는 Keychain 에만 있음. */
data class UserAccount(
    val userId: UserId,
    val role: Role,
    val displayName: String,
    val passwordHash: String,
    val totpEnrolledAt: Instant?,
    val totpLastCounter: Long,
    val failedLogins: Int,
    val lockedUntil: Instant?,
    val status: UserStatus,
    val tossKeyDecision: TossDecision,
    val autoStopOnLogout: Boolean,
    val email: String?,
    val slackUserId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isTotpEnrolled: Boolean
        get() = totpEnrolledAt != null

    companion object {
        /** 한 설치의 최대 인원. 5번째는 서비스가 막음. */
        const val MAX_USERS = 4
        const val DISPLAY_NAME_MAX = 20
    }
}
