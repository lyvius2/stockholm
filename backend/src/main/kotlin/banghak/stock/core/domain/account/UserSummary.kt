package banghak.stock.core.domain.account

import banghak.stock.core.domain.identity.Role
import banghak.stock.core.domain.identity.UserId
import java.time.Instant

/** 로그인 모달·회원 관리 표가 보는 사용자 요약. 비밀·키 값 없음. */
data class UserSummary(
    val userId: UserId,
    val displayName: String,
    val role: Role,
    val status: UserStatus,
    val isTotpEnrolled: Boolean,
    val tossKeyDecision: TossDecision,
    val lockedUntil: Instant?,
    val createdAt: Instant,
)
