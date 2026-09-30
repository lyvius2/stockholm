package banghak.stock.engine.application.trading

import banghak.stock.core.domain.error.ConfirmationRequiredException
import banghak.stock.core.domain.error.GuardrailViolationException
import banghak.stock.core.domain.guardrail.GuardrailVerdict
import banghak.stock.core.domain.guardrail.HighValueOrder

/**
 * 가드레일 판정을 보내도 되는 요청으로 바꾸는 규칙.
 * 새 주문·정정·조건주문이 함께 씀.
 */
internal object GuardrailApproval {
    /**
     * 위반이 없고 노트를 사람이 모두 확인했을 때만 통과함.
     *
     * @throws GuardrailViolationException 위반이 있으면 발생함
     * @throws ConfirmationRequiredException 확인하지 않은 노트가 있으면 발생함
     */
    fun requireApproved(verdict: GuardrailVerdict, confirmedRules: Set<String>) {
        if (verdict is GuardrailVerdict.Rejected)
            throw GuardrailViolationException(verdict.violations.map { "${it.rule}: ${it.reason}" })
        val unconfirmed = verdict.notes.filterNot { it.rule in confirmedRules }
        if (unconfirmed.isNotEmpty())
            throw ConfirmationRequiredException(unconfirmed.map { "${it.rule}: ${it.text}" })
    }

    /** 사람이 확인한 고액 노트가 있으면 증권사에 고액 확인을 보냄. */
    fun isHighValueConfirmed(verdict: GuardrailVerdict): Boolean =
        verdict.notes.any { it.rule == HighValueOrder.NAME }
}
