package banghak.stock.core.domain.trading

enum class OrderSide {
    BUY,
    SELL,
}

/** MARKET은 두 경우뿐임: 미국 소수점 보유분 매도, 미국 금액(orderAmount) 매수. 자동 주문은 LIMIT만. */
enum class OrderKind {
    LIMIT,
    MARKET,
}

/** DAY 기본. CLS는 미국 종가지정가(LIMIT만), OPG는 국내 시가단일가. */
enum class TimeInForce {
    DAY,
    CLS,
    OPG,
}

/** 주문의 출처. lot 출처(`BuyOrigin`) 셋에 자동 매도를 더한 것. */
enum class OrderOrigin {
    MANUAL,
    AI_RECOMMENDED,
    AUTO_BUY,
    AUTO_SELL,
}

/** 토스 주문 상태 10개 + UNKNOWN. UNKNOWN은 결과를 모르는 상태이며 재시도 전에 반드시 조회함. */
enum class OrderStatus {
    PENDING,
    PARTIALLY_FILLED,
    PENDING_CANCEL,
    PENDING_AMEND,
    FILLED,
    CANCELLED,
    REJECTED,
    CANCEL_REJECTED,
    AMEND_REJECTED,
    REPLACED,
    UNKNOWN,
}
