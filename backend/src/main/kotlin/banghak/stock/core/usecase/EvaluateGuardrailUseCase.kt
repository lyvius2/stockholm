package banghak.stock.core.usecase

import banghak.stock.core.domain.guardrail.GuardrailVerdict
import banghak.stock.core.domain.trading.OrderIntent

/**
 * 주문 의도를 가드레일에 통과시킴.
 * 모든 주문(수동·자동)은 증권사에 보내기 전에 이 판정을 받아야 함.
 * 우회 경로를 두지 않음.
 * 구현은 계좌 스냅샷·장 달력·시세·환율을 모아 컨텍스트를 만들고 규칙 묶음을 돌림.
 */
interface EvaluateGuardrailUseCase {
    fun evaluate(intent: OrderIntent): GuardrailVerdict
}
