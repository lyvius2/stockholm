package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.Quantity
import java.math.BigDecimal

/** 원장을 시작할 때 기초 lot 으로 만들 한 종목. */
data class OpeningPosition(val symbol: Symbol, val quantity: Quantity, val averagePrice: Money)

/** 원장을 시작할 때 만들 기초 lot 과 건너뛸 대기열 체결. */
data class OpeningPlan(val positions: List<OpeningPosition>, val skippedFillIds: Set<String>)

/**
 * 원장 시작 계획.
 * 체결 시각과 보유 조회 시각을 비교하지 않고 수량으로 맞춤(보유 조회가 방금 체결을 늦게 반영해도 체결이 빠지지 않게).
 * 기초 수량 = 보유 − (대기열 매수 − 대기열 매도).
 * 대기열 체결은 모두 반영하고, 기초 lot 은 대기열로 설명되지 않는 보유만 담음.
 */
object OpeningLedger {
    fun plan(holdings: List<BrokerHolding>, pending: List<QueuedFill>): OpeningPlan {
        val bySymbol = holdings.associateBy { it.symbol }
        val netQueued = pending.groupBy { it.fill.symbol }.mapValues { (_, fills) -> netOf(fills) }
        val positions = mutableListOf<OpeningPosition>()
        val skipped = mutableSetOf<String>()
        (bySymbol.keys + netQueued.keys).forEach { symbol ->
            val held = bySymbol[symbol]
            val opening =
                (held?.quantity?.value ?: BigDecimal.ZERO) - (netQueued[symbol] ?: BigDecimal.ZERO)
            when {
                opening.signum() <= 0 -> Unit
                // 보유에 없는데 판 것이 더 많으면 원장 시작 전에 산 주식이라 매입가를 알 수 없음
                held == null -> skipped += pending.filter { it.fill.symbol == symbol }.map { it.id }
                else ->
                    positions +=
                        OpeningPosition(symbol, Quantity.of(opening), held.averagePurchasePrice)
            }
        }
        return OpeningPlan(positions, skipped)
    }

    private fun netOf(fills: List<QueuedFill>): BigDecimal =
        fills.fold(BigDecimal.ZERO) { sum, item ->
            val quantity = item.fill.quantity.value
            if (item.fill.side == OrderSide.BUY) sum + quantity else sum - quantity
        }
}
