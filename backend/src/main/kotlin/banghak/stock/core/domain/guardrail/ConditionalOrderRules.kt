package banghak.stock.core.domain.guardrail

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.guardrail.GuardrailFinding.Clear
import banghak.stock.core.domain.guardrail.GuardrailFinding.Violation
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.trading.ConditionalOrderIntent
import java.time.Instant
import java.time.LocalDate

/**
 * 조건주문 등록 순간의 가드레일 입력.
 * 감시와 발동은 토스 서버가 하므로 계좌·장 상태가 아니라 등록 내용만 판정함.
 * [today] 는 그 시장 시간대의 오늘임.
 */
data class ConditionalGuardrailContext(
    val intent: ConditionalOrderIntent,
    val today: LocalDate,
    val fxToKrw: ExchangeRate?,
    val now: Instant,
) {
    init {
        if (fxToKrw != null && fxToKrw.from != intent.market.currency)
            throw InvalidValueException(
                "환율 ${fxToKrw.from} 이 조건주문 통화 ${intent.market.currency} 와 다름"
            )
    }
}

/**
 * 조건주문 규칙 하나.
 * 상태가 없고 컨텍스트만 봄.
 */
interface ConditionalGuardrail {
    val name: String

    fun check(context: ConditionalGuardrailContext): GuardrailFinding
}

/**
 * 만료일이 이미 지난 조건주문은 등록하지 않음.
 * 오늘 만료는 허용함.
 */
class ConditionalExpiry : ConditionalGuardrail {
    override val name = "ConditionalExpiry"

    override fun check(context: ConditionalGuardrailContext): GuardrailFinding =
        if (context.intent.expireDate.isBefore(context.today))
            Violation(name, "만료일 ${context.intent.expireDate} 이 이미 지남")
        else Clear
}

/**
 * 조건주문의 고액 판정.
 * 발동하면 조건마다 주문이 하나씩 나가므로 가장 큰 조건 금액으로 판정함.
 * 수동 주문과 같은 기준·규칙 이름이라 확인한 노트가 토스 고액 확인으로 이어짐.
 */
class ConditionalHighValue : ConditionalGuardrail {
    override val name = HighValueOrder.NAME

    override fun check(context: ConditionalGuardrailContext): GuardrailFinding =
        HighValueOrder.judge(
            HighValueOrder.toKrw(context.intent.largestNotional(), context.fxToKrw, context.now)
        )
}

/** 조건주문(F1 조건 주문 탭)에 거는 규칙 묶음. */
object ConditionalOrderGuardrails {
    private val rules: List<ConditionalGuardrail> =
        listOf(ConditionalExpiry(), ConditionalHighValue()).sortedBy { it.name }

    fun evaluate(context: ConditionalGuardrailContext): GuardrailVerdict =
        GuardrailVerdict.of(rules.map { it.check(context) })
}
