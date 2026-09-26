package banghak.stock.core.domain.portfolio

/** lot의 출처. 손익 분석·자동 매수 한도 계산·학습의 공통 키. */
enum class BuyOrigin {
    MANUAL,
    AI_RECOMMENDED,
    AUTO_BUY,
}
