package banghak.stock.core.usecase

import banghak.stock.core.domain.guardrail.GuardrailVerdict
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.ConditionalOrderIntent
import banghak.stock.core.domain.trading.OrderIntent

/**
 * 주문 의도를 가드레일에 통과시킴.
 * 모든 주문(수동·자동)은 증권사에 보내기 전에 이 판정을 받아야 함.
 * 우회 경로를 두지 않음.
 * 구현은 계좌 스냅샷·장 달력·시세·환율을 모아 컨텍스트를 만들고 규칙 묶음을 돌림.
 */
interface EvaluateGuardrailUseCase {
    /**
     * 주문 의도를 판정함.
     * [clientOrderId] 는 이 주문을 보낼 때 쓸 멱등 키이며 같은 키의 재접수를 막는 데 씀.
     */
    fun evaluate(intent: OrderIntent, clientOrderId: ClientOrderId): GuardrailVerdict

    /**
     * 조건주문 등록·수정을 판정함.
     * 감시와 발동은 토스 서버가 하므로 등록 순간이 Stockholm 가드레일의 유일한 관문임.
     */
    fun evaluateConditional(intent: ConditionalOrderIntent): GuardrailVerdict
}
