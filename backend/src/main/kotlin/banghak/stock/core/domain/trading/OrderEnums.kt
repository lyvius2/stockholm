package banghak.stock.core.domain.trading

enum class OrderSide {
    BUY,
    SELL,
}

/**
 * MARKET은 두 경우뿐임: 미국 소수점 보유분 매도, 미국 금액(orderAmount) 매수.
 * 자동 주문은 LIMIT만.
 */
enum class OrderKind {
    LIMIT,
    MARKET,
}

/**
 * DAY 기본.
 * CLS는 미국 종가지정가(LIMIT만), OPG는 국내 시가단일가.
 */
enum class TimeInForce {
    DAY,
    CLS,
    OPG,
}

/**
 * 주문의 출처.
 * lot 출처(`BuyOrigin`) 셋에 자동 매도를 더한 것.
 */
enum class OrderOrigin {
    MANUAL,
    AI_RECOMMENDED,
    AUTO_BUY,
    AUTO_SELL,
}

/**
 * 토스 주문 상태 10개 + UNKNOWN.
 * UNKNOWN은 결과를 모르는 상태이며 재시도 전에 반드시 조회함.
 */
enum class OrderStatus(val isOpen: Boolean, val isInFlight: Boolean) {
    PENDING(isOpen = true, isInFlight = false),
    PARTIALLY_FILLED(isOpen = true, isInFlight = false),
    PENDING_CANCEL(isOpen = true, isInFlight = true),
    PENDING_AMEND(isOpen = true, isInFlight = true),
    FILLED(isOpen = false, isInFlight = false),
    CANCELLED(isOpen = false, isInFlight = false),
    REJECTED(isOpen = false, isInFlight = false),
    CANCEL_REJECTED(isOpen = false, isInFlight = false),
    AMEND_REJECTED(isOpen = false, isInFlight = false),
    REPLACED(isOpen = false, isInFlight = false),
    UNKNOWN(isOpen = false, isInFlight = true),
}
