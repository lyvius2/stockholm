package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.Quantity
import java.time.Instant

/**
 * 매도 체결 한 건이 lot 하나를 소진한 기록.
 * 기간별 실현손익의 원천임(우리 DB 가 원천, 토스 값은 대조용).
 * [realizedKrw] = [tradePnlKrw] + [fxPnlKrw] 로 정의해 두 값의 합이 항상 원화 실현손익과 같음.
 */
data class LotDisposal(
    val lotId: LotId,
    val brokerOrderId: String,
    val quantity: Quantity,
    val sellPrice: Money,
    val buyUnitCost: Money,
    val fee: Money,
    val tax: Money,
    val fxAtBuy: ExchangeRate?,
    val fxAtSell: ExchangeRate?,
    val realized: Money,
    val tradePnlKrw: Money?,
    val fxPnlKrw: Money?,
    val holdingDays: Long,
    val lotOrigin: BuyOrigin,
    val disposedAt: Instant,
) {
    val realizedKrw: Money?
        get() = tradePnlKrw?.let { trade -> fxPnlKrw?.let(trade::plus) }
}
