package banghak.stock.core.domain.guardrail

/**
 * 규칙을 전부 돌리고 위반과 노트를 모음.
 * 순서는 이름순으로 고정해 로그가 안정적임.
 * 첫 위반에서 멈추지 않아 화면이 위반 목록 전체를 보임.
 */
class GuardrailChain(rules: List<Guardrail>) {
    private val rules = rules.sortedBy { it.name }

    fun evaluate(context: GuardrailContext): GuardrailVerdict {
        val findings = rules.filter { it.appliesTo(context.intent) }.map { it.check(context) }
        val violations = findings.filterIsInstance<GuardrailFinding.Violation>()
        val notes = findings.filterIsInstance<GuardrailFinding.Note>()
        return if (violations.isEmpty()) GuardrailVerdict.Passed(notes)
        else GuardrailVerdict.Rejected(violations, notes)
    }
}
