package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.Quantity
import java.math.BigDecimal
import java.time.Instant

/**
 * 막힌 매도(기록된 lot 보다 많이 판 매도)가 있는 종목을 증권사 보유와 대조함.
 * 차이 = 보유 − (기록된 lot + 대기열 체결의 순증감).
 * 대기열은 그 종목의 미처리 체결을 체결 순서대로 담고 첫 건이 막힌 매도임.
 */
object ShortageReconciliation {
    sealed interface Decision {
        /**
         * 빠진 매수.
         * [quantity] 만큼 [averagePrice] 로 [boughtAt] 에 기초 lot 을 채움.
         */
        data class FillGap(val quantity: Quantity, val averagePrice: Money, val boughtAt: Instant) :
            Decision

        /**
         * 순서 문제.
         * 막힌 매도보다 뒤에 온 이 매수들을 먼저 반영함.
         */
        data class ApplyBuysFirst(val fillIds: List<String>) : Decision

        data class NeedsReview(val reason: String) : Decision
    }

    fun decide(
        holding: BrokerHolding?,
        recordedOpen: Quantity,
        pending: List<QueuedFill>,
    ): Decision {
        val blockedSell = pending.first()
        val net = pending.fold(BigDecimal.ZERO) { sum, item -> sum + signed(item) }
        val gap = (holding?.quantity?.value ?: BigDecimal.ZERO) - (recordedOpen.value + net)
        return when {
            gap.signum() > 0 && holding != null ->
                // 빠진 매수가 언제였는지 모르므로 막힌 매도 바로 앞에 둬 그 매도가 소진하게 함
                Decision.FillGap(
                    Quantity.of(gap),
                    holding.averagePurchasePrice,
                    blockedSell.fill.executedAt.minusMillis(1),
                )
            gap.signum() > 0 -> Decision.NeedsReview("보유에 없는 종목을 기록보다 많이 팔아 매입가를 알 수 없음")
            gap.signum() < 0 -> Decision.NeedsReview("기록이 보유보다 많음(빠진 매도가 있을 수 있음)")
            else -> laterBuysOrReview(pending.drop(1))
        }
    }

    // 실시간 채널은 받은 시각을 체결 시각으로 써서, 먼저 체결된 매수가 매도보다 뒤에 설 수 있음
    private fun laterBuysOrReview(after: List<QueuedFill>): Decision {
        val buys = after.filter { it.fill.side == OrderSide.BUY }.map { it.id }
        return if (buys.isEmpty()) Decision.NeedsReview("보유와 기록은 맞지만 매도할 lot 이 없음")
        else Decision.ApplyBuysFirst(buys)
    }

    private fun signed(item: QueuedFill): BigDecimal =
        if (item.fill.side == OrderSide.BUY) item.fill.quantity.value
        else item.fill.quantity.value.negate()
}
