package banghak.stock.core.domain.trading

import banghak.stock.core.domain.automation.StrategyId
import banghak.stock.core.domain.identity.DeviceId
import java.time.LocalDate

/**
 * 어디서 온 주문인가.
 * 감사 로그·학습·이중 주문 방어의 키.
 * 자동 주문 둘만 [isAutomatic] 임.
 */
sealed interface OrderTrigger {
    val isAutomatic: Boolean
        get() = this is AutoBuyTrigger || this is AutoSellTrigger
}

/** 사람이 주문 모달에서 냄. */
data class ManualTrigger(val device: DeviceId) : OrderTrigger

/**
 * 사람이 미체결 주문을 정정함.
 * 자동 주문의 정정이라도 트리거는 사람이므로 한도 규칙의 대상이 아님.
 */
data class ManualAmendTrigger(val device: DeviceId, val amendedBrokerOrderId: String) : OrderTrigger

/**
 * AI 추천을 보고 사람이 승인해 냄.
 * 추천 id 는 recommend 패키지가 생길 때까지 문자열임.
 */
data class RecommendationTrigger(val recommendationId: String) : OrderTrigger

/**
 * 자동 매수.
 * (전략, 거래일, 회차)가 결정적 `ClientOrderId` 의 재료임.
 */
data class AutoBuyTrigger(val strategy: StrategyId, val tradingDay: LocalDate, val sequence: Int) :
    OrderTrigger

/**
 * 자동 매도.
 * 근거 토론 id 는 debate 패키지가 생길 때까지 문자열임.
 */
data class AutoSellTrigger(
    val debateSessionId: String,
    val tradingDay: LocalDate,
    val sequence: Int,
) : OrderTrigger
