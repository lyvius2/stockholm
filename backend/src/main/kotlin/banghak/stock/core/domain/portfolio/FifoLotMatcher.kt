package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.Quantity
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/**
 * 매도 체결 한 건.
 * 수수료·세금은 체결 전체 값이며 lot 별로 수량 비례 배분함.
 */
data class Sale(
    val brokerOrderId: String,
    val symbol: Symbol,
    val quantity: Quantity,
    val price: Money,
    val fee: Money,
    val tax: Money,
    val fxAtSell: ExchangeRate?,
    val executedAt: Instant,
)

data class DisposalResult(val disposals: List<LotDisposal>, val lotsAfter: List<Lot>)

/**
 * 매도 수량을 lot 에 선입선출로 배분함.
 * 토스 앱 잔고 화면이 "잔고(선입선출)" 라 증권사 계산과 일치함.
 * 자동 매도 한도(90%)는 종목 기준 수량으로 세므로 소진 순서와 무관함.
 */
object FifoLotMatcher {
    fun dispose(lots: List<Lot>, sale: Sale): DisposalResult {
        val queue = lots.filter { it.isOpen && it.symbol == sale.symbol }.sortedBy { it.boughtAt }
        val held = queue.fold(Quantity.ZERO) { sum, lot -> sum.plus(lot.remainingQuantity) }
        if (sale.quantity.isGreaterThan(held))
            throw InvalidValueException("매도 수량 ${sale.quantity} 이 보유 $held 를 넘음: ${sale.symbol}")
        if (sale.symbol.market.currency != Currency.KRW && sale.fxAtSell == null)
            throw InvalidValueException("해외 매도에는 매도 시점 환율이 필요함: ${sale.symbol}")

        val disposals = mutableListOf<LotDisposal>()
        val lotsAfter = lots.toMutableList()
        var left = sale.quantity
        var feeLeft = sale.fee
        var taxLeft = sale.tax
        for (lot in queue) {
            if (left.isZero) break
            val taken =
                if (lot.remainingQuantity.isGreaterThan(left)) left else lot.remainingQuantity
            left = left.minus(taken)
            // 비용은 수량 비례로 나누고 반올림 나머지는 마지막 lot 이 떠안아 합이 정확히 맞음
            val share = taken.value.divide(sale.quantity.value, SHARE_SCALE, RoundingMode.HALF_EVEN)
            val fee = if (left.isZero) feeLeft else sale.fee.times(share)
            val tax = if (left.isZero) taxLeft else sale.tax.times(share)
            feeLeft = feeLeft.minus(fee)
            taxLeft = taxLeft.minus(tax)
            disposals += disposal(lot, sale, taken, fee, tax)
            lotsAfter[lotsAfter.indexOf(lot)] = lot.afterSelling(taken)
        }
        return DisposalResult(disposals, lotsAfter)
    }

    private fun disposal(
        lot: Lot,
        sale: Sale,
        taken: Quantity,
        fee: Money,
        tax: Money,
    ): LotDisposal {
        val realized = sale.price.minus(lot.unitCost).times(taken).minus(fee).minus(tax)
        val fxSell = sale.fxAtSell
        val fxBuy = lot.fxAtBuy
        val tradePnlKrw = if (fxSell == null) realized else realized.convert(fxSell)
        val fxPnlKrw =
            if (fxSell == null || fxBuy == null) Money.zero(Currency.KRW)
            else
                Money.of(
                    lot.unitCost.amount * taken.value * (fxSell.rate - fxBuy.rate),
                    Currency.KRW,
                )
        return LotDisposal(
            lotId = lot.id,
            brokerOrderId = sale.brokerOrderId,
            quantity = taken,
            sellPrice = sale.price,
            buyUnitCost = lot.unitCost,
            fee = fee,
            tax = tax,
            fxAtBuy = fxBuy,
            fxAtSell = fxSell,
            realized = realized,
            tradePnlKrw = tradePnlKrw,
            fxPnlKrw = fxPnlKrw,
            holdingDays = Duration.between(lot.boughtAt, sale.executedAt).toDays(),
            lotOrigin = lot.origin,
            disposedAt = sale.executedAt,
        )
    }

    private const val SHARE_SCALE = 10
}
