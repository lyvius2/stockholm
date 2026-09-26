package banghak.stock.core.domain.account

import banghak.stock.core.domain.error.IllegalSetupTransitionException

/** 설치 단위 최초 구동 상태. 전이는 한 칸씩 앞으로만 감. COMPLETE 전에는 `/setup/…` 와 헬스 외 모든 로컬 API 가 닫혀 있음. */
enum class SetupState {
    NOT_STARTED,
    ADMIN_CREATED,
    SHARED_KEYS_DONE,
    TOSS_DECIDED,
    COMPLETE;

    val isComplete: Boolean
        get() = this == COMPLETE

    fun next(): SetupState =
        entries.getOrNull(ordinal + 1) ?: throw IllegalSetupTransitionException("$this 다음 상태가 없음")

    /** 정확히 다음 상태로만 갈 수 있음. */
    fun advanceTo(target: SetupState): SetupState {
        if (target != next()) throw IllegalSetupTransitionException("$this 에서 $target 으로 갈 수 없음")
        return target
    }

    fun requireAtLeast(minimum: SetupState) {
        if (ordinal < minimum.ordinal)
            throw IllegalSetupTransitionException("$minimum 이후에만 할 수 있음(현재 $this)")
    }

    fun requireExactly(expected: SetupState) {
        if (this != expected)
            throw IllegalSetupTransitionException("$expected 상태에서만 할 수 있음(현재 $this)")
    }
}
