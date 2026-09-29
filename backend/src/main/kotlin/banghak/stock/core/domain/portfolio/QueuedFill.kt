package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.trading.FillIncrement

/** 체결 대기열의 처리 상태. */
enum class FillState {
    /** lot 에 아직 반영하지 않음. */
    PENDING,

    /**
     * 반영하려 했으나 막힘(lot 부족 등).
     * 원인이 풀리면 다시 반영함.
     */
    BLOCKED,
    DONE,

    /** 원장 시작 전 체결이라 반영하지 않음(그 결과는 기초 lot 에 들어 있음). */
    SKIPPED,
}

/**
 * lot 반영을 기다리는 체결 증분.
 * 주문 상태를 반영하는 트랜잭션에서 함께 넣으므로(아웃박스) lot 처리가 실패해도 체결을 잃지 않음.
 */
data class QueuedFill(
    val id: String,
    val fill: FillIncrement,
    val state: FillState,
    val reason: String?,
)
