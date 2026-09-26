package banghak.stock.core.domain.account

import banghak.stock.core.domain.identity.UserId
import java.time.Instant

/** 토스 키에 대한 사용자의 선택. LATER 면 조회 제한 모드. */
enum class TossDecision {
    NONE,
    REGISTERED,
    LATER,
}

enum class LlmPreset {
    BALANCED,
    QUALITY,
    COST,
    LOCAL,
}

/** 설치 한 건. 데몬 하나에 한 행. */
data class Installation(
    val installationId: String,
    val setupState: SetupState,
    val adminUserId: UserId?,
    val llmPreset: LlmPreset?,
    val lastLoginUserId: UserId?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun advance(target: SetupState, now: Instant): Installation =
        copy(setupState = setupState.advanceTo(target), updatedAt = now)
}
