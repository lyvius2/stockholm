package banghak.stock.core.domain.guardrail

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.TradingDay
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.portfolio.PortfolioSnapshot
import banghak.stock.core.domain.trading.BrokerOrder
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.OrderIntent
import banghak.stock.core.domain.trading.Quote
import java.time.Instant

/**
 * 가드레일이 보는 모든 입력.
 * I/O 는 engine 이 하고 이 객체를 만들어 넘김.
 * 규칙은 이 객체만 봄.
 * 생성 시 주문·스냅샷·거래일·현재가가 같은 사용자·시장·종목인지 검사함.
 * 다른 사용자나 다른 시장의 데이터로는 판정하지 않음.
 * [todayOrders] 는 Stockholm 이 오늘 낸 주문 전체(체결·취소 포함)이며 같은 멱등 키의 재접수를 막는 데 씀.
 */
data class GuardrailContext(
    val intent: OrderIntent,
    val clientOrderId: ClientOrderId,
    val snapshot: PortfolioSnapshot,
    val todayOrders: List<BrokerOrder>,
    val tradingDay: TradingDay,
    val quote: Quote?,
    val fxToKrw: ExchangeRate?,
    val now: Instant,
) {
    init {
        if (snapshot.userId != intent.userId)
            throw InvalidValueException("스냅샷 사용자 ${snapshot.userId} 이 주문 사용자 ${intent.userId} 와 다름")
        if (snapshot.market != intent.market)
            throw InvalidValueException("스냅샷 시장 ${snapshot.market} 이 주문 시장 ${intent.market} 과 다름")
        if (tradingDay.market != intent.market)
            throw InvalidValueException("거래일 시장 ${tradingDay.market} 이 주문 시장 ${intent.market} 과 다름")
        if (quote != null && quote.symbol != intent.symbol)
            throw InvalidValueException("현재가 종목 ${quote.symbol} 이 주문 종목 ${intent.symbol} 과 다름")
        todayOrders
            .firstOrNull { it.intent.userId != intent.userId }
            ?.let { throw InvalidValueException("다른 사용자의 주문이 섞임: ${it.brokerOrderId}") }
    }
}

/**
 * 규칙 하나의 판정.
 * 통과·확인 노트·위반 중 하나임.
 */
sealed interface GuardrailFinding {
    data object Clear : GuardrailFinding

    /**
     * 거부는 아니지만 사람이 확인해야 하는 사항.
     * 화면은 확인 창을 띄움.
     */
    data class Note(val rule: String, val text: String) : GuardrailFinding

    data class Violation(
        val rule: String,
        val reason: String,
        val limit: Money? = null,
        val actual: Money? = null,
    ) : GuardrailFinding
}

/**
 * 규칙 전체의 판정.
 * 위반이 하나라도 있으면 [Rejected] 임.
 */
sealed interface GuardrailVerdict {
    val notes: List<GuardrailFinding.Note>

    val isPassed: Boolean
        get() = this is Passed

    data class Passed(override val notes: List<GuardrailFinding.Note>) : GuardrailVerdict

    data class Rejected(
        val violations: List<GuardrailFinding.Violation>,
        override val notes: List<GuardrailFinding.Note>,
    ) : GuardrailVerdict

    companion object {
        /** 규칙마다의 판정을 모아 전체 판정을 만듦. */
        fun of(findings: List<GuardrailFinding>): GuardrailVerdict {
            val violations = findings.filterIsInstance<GuardrailFinding.Violation>()
            val notes = findings.filterIsInstance<GuardrailFinding.Note>()
            return if (violations.isEmpty()) Passed(notes) else Rejected(violations, notes)
        }
    }
}

/**
 * 규칙 하나.
 * 구현체는 상태가 없고 컨텍스트만 봄.
 * [appliesTo] 로 대상 주문을 거름(수동 주문에는 한도 규칙이 걸리지 않음 등).
 */
interface Guardrail {
    val name: String

    fun appliesTo(intent: OrderIntent): Boolean = true

    fun check(context: GuardrailContext): GuardrailFinding
}
