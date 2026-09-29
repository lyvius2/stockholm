package banghak.stock.core.domain.guardrail

import banghak.stock.core.domain.guardrail.GuardrailFinding.Clear
import banghak.stock.core.domain.guardrail.GuardrailFinding.Note
import banghak.stock.core.domain.guardrail.GuardrailFinding.Violation
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.MarketSession
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.OrderIntent
import banghak.stock.core.domain.trading.OrderKind
import banghak.stock.core.domain.trading.OrderSide
import java.time.Duration
import java.time.Instant

/**
 * 오래된 스냅샷으로는 주문하지 않음.
 * 정확히 [maxAge] 만큼 지난 스냅샷은 아직 씀.
 */
class SnapshotFreshness(private val maxAge: Duration = GuardrailDefaults.SNAPSHOT_MAX_AGE) :
    Guardrail {
    override val name = "SnapshotFreshness"

    override fun check(context: GuardrailContext): GuardrailFinding =
        if (context.snapshot.isStale(context.now, maxAge))
            Violation(name, "계좌 스냅샷이 ${maxAge.seconds}초보다 오래됨")
        else Clear
}

/**
 * 장이 열려 있어야 주문함.
 * 정규장 밖(데이마켓·프리·애프터)에서는 지정가만 냄.
 */
class SessionOpen : Guardrail {
    override val name = "SessionOpen"

    override fun check(context: GuardrailContext): GuardrailFinding {
        val session = context.tradingDay.sessionAt(context.now)
        if (session == MarketSession.CLOSED) return Violation(name, "장이 열려 있지 않음")
        if (session != MarketSession.REGULAR && context.intent.kind != OrderKind.LIMIT)
            return Violation(name, "정규장 밖에서는 지정가만 낼 수 있음: $session")
        return Clear
    }
}

/**
 * 시장가 주문의 범위.
 * 토스는 시장가를 미국 소수점 매도와 미국 금액 매수에만 허용하고, 정규장 시작부터 종료 1시간 전까지만 받음.
 * 자동 주문의 시장가는 무조건 거부함.
 */
class MarketOrderScope : Guardrail {
    override val name = "MarketOrderScope"

    override fun appliesTo(intent: OrderIntent): Boolean = intent.kind == OrderKind.MARKET

    override fun check(context: GuardrailContext): GuardrailFinding {
        val intent = context.intent
        if (intent.isAutomatic) return Violation(name, "자동 주문은 시장가를 낼 수 없음")
        if (intent.market != Market.US) return Violation(name, "시장가는 미국 시장에서만 낼 수 있음")
        if (intent.side == OrderSide.SELL && intent.quantity?.isWholeShares != false)
            return Violation(name, "미국 시장가 매도는 소수점 보유분에만 씀. 정수 주수는 지정가로 냄")
        val regular =
            context.tradingDay.window(MarketSession.REGULAR)
                ?: return Violation(name, "오늘은 정규장이 없음")
        // 종료 정확히 1시간 전까지는 접수하고 그 뒤는 거부함
        val lastAccepted = regular.end.minus(GuardrailDefaults.MARKET_ORDER_CLOSING_MARGIN)
        if (context.now.isBefore(regular.start) || context.now.isAfter(lastAccepted))
            return Violation(name, "시장가는 정규장 시작부터 종료 1시간 전까지만 접수함")
        return Clear
    }
}

/** 같은 종목의 반대 방향 미체결이 있으면 토스가 409 로 거부하므로 미리 막음. */
class OppositeSideOpenOrder : Guardrail {
    override val name = "OppositeSideOpenOrder"

    override fun check(context: GuardrailContext): GuardrailFinding {
        val intent = context.intent
        val opposite =
            context.snapshot.openOrders.any {
                it.isOpen && it.intent.symbol == intent.symbol && it.intent.side != intent.side
            }
        return if (opposite) Violation(name, "같은 종목의 반대 방향 미체결 주문이 있음") else Clear
    }
}

/**
 * 이중 주문 방지.
 * 같은 멱등 키의 주문이 오늘 이미 있으면(체결·취소 포함) 수동·자동 모두 거부함.
 * 같은 종목·방향 미체결은 자동 주문이면 거부하고, 수동 주문은 사람이 알고 내는 것일 수 있어 노트만 남김.
 */
class DuplicateIntent : Guardrail {
    override val name = "DuplicateIntent"

    override fun check(context: GuardrailContext): GuardrailFinding {
        val intent = context.intent
        val sameKey =
            (context.snapshot.openOrders + context.todayOrders).any {
                it.clientOrderId == context.clientOrderId
            }
        if (sameKey) return Violation(name, "같은 멱등 키(${context.clientOrderId})의 주문이 오늘 이미 있음")
        val sameSideOpen =
            context.snapshot.openOrders.any {
                it.isOpen && it.intent.symbol == intent.symbol && it.intent.side == intent.side
            }
        if (!sameSideOpen) return Clear
        return if (intent.isAutomatic) Violation(name, "같은 종목·방향의 미체결 주문이 이미 있음")
        else Note(name, "같은 종목·방향의 미체결 주문이 이미 있음")
    }
}

/**
 * 고액 주문.
 * 원화 환산 1억원 이상이면 확인 노트(토스 `confirmHighValueOrder` 필수), 30억원을 넘으면 거부함.
 * 해외 주문은 환율이 있어야 판정할 수 있고, 시장가 매도는 현재가가 있어야 금액을 앎.
 */
class HighValueOrder : Guardrail {
    override val name = "HighValueOrder"

    override fun check(context: GuardrailContext): GuardrailFinding {
        val notionalKrw =
            notionalInKrw(context) ?: return Violation(name, "주문 금액을 판정할 신선한 현재가·환율이 없음")
        if (notionalKrw > GuardrailDefaults.MAX_ORDER_VALUE)
            return Violation(
                name,
                "30억원을 넘는 주문은 낼 수 없음",
                GuardrailDefaults.MAX_ORDER_VALUE,
                notionalKrw,
            )
        if (notionalKrw >= GuardrailDefaults.HIGH_VALUE_ORDER)
            return Note(name, "1억원 이상 고액 주문. 확인이 필요함")
        return Clear
    }

    // 오래된 현재가·환율로 금액을 낮게 잡으면 확인 노트나 30억 제한을 놓치므로 신선한 값만 씀
    private fun notionalInKrw(context: GuardrailContext): Money? {
        val intent = context.intent
        val needsQuote = intent.kind == OrderKind.MARKET && intent.side == OrderSide.SELL
        val quote =
            context.quote?.takeIf { isFresh(it.asOf, GuardrailDefaults.QUOTE_MAX_AGE, context) }
        if (needsQuote && quote == null) return null
        val notional = intent.notional(quote?.last)
        if (notional.currency == Currency.KRW) return notional
        val fx =
            context.fxToKrw?.takeIf {
                it.from == notional.currency &&
                    it.to == Currency.KRW &&
                    isFresh(it.asOf, GuardrailDefaults.FX_MAX_AGE, context)
            } ?: return null
        return notional.convert(fx)
    }

    private fun isFresh(asOf: Instant, maxAge: Duration, context: GuardrailContext): Boolean =
        Duration.between(asOf, context.now) <= maxAge
}

/**
 * 수동 주문(F1)에 거는 규칙 묶음.
 * 한도 규칙은 자동 주문에만 걸리므로 여기 없음.
 */
object ManualOrderGuardrails {
    fun standard(): GuardrailChain =
        GuardrailChain(
            listOf(
                SnapshotFreshness(),
                SessionOpen(),
                MarketOrderScope(),
                OppositeSideOpenOrder(),
                DuplicateIntent(),
                HighValueOrder(),
            )
        )
}
