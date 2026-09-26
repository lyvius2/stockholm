package banghak.stock.core.domain.automation

/** 자동화 실행 단계. 모의 → 승인 후 실행 → 완전 자동 순으로만 올라감. */
enum class ExecutionStage {
    SIMULATED,
    APPROVAL_REQUIRED,
    FULLY_AUTOMATIC,
}
