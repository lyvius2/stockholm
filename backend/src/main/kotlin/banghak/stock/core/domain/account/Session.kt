package banghak.stock.core.domain.account

import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.Role
import banghak.stock.core.domain.identity.UserId
import java.time.Duration
import java.time.Instant

/** NORMAL 은 로그인 세션, SETUP 은 마법사 ① 뒤 ②~④ 에만 쓰는 짧은 세션. */
enum class SessionKind {
    NORMAL,
    SETUP,
}

/** 세션 한 건. `sessionId` 는 토큰의 해시라 토큰 원문은 발급 응답에만 있고 저장되지 않음. */
data class Session(
    val sessionId: String,
    val userId: UserId,
    val deviceId: DeviceId,
    val kind: SessionKind,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val lastStepUpAt: Instant?,
    val revokedAt: Instant?,
) {
    fun isActive(now: Instant): Boolean = revokedAt == null && now.isBefore(expiresAt)

    /** step-up 은 직전 TOTP 재인증 뒤 5분 안에서만 유효함. */
    fun hasFreshStepUp(now: Instant): Boolean =
        lastStepUpAt != null && Duration.between(lastStepUpAt, now) < STEP_UP_VALIDITY

    companion object {
        val NORMAL_TTL: Duration = Duration.ofHours(12)
        val SETUP_TTL: Duration = Duration.ofHours(1)
        val STEP_UP_VALIDITY: Duration = Duration.ofMinutes(5)
    }
}

/** 인증된 요청의 주체. 컨트롤러는 이것만 봄. */
data class Principal(val userId: UserId, val role: Role, val session: Session) {
    val isAdmin: Boolean
        get() = role == Role.ADMIN
}
