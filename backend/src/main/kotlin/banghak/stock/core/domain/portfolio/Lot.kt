package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.Quantity
import java.time.Duration
import java.time.Instant

/**
 * 매수 한 건.
 * 부분 매도로 [remainingQuantity] 만 줄고 매입 원가·환율·시각은 바뀌지 않음.
 * 해외 lot 은 반드시 매수 시점 환율을 가짐(노출액은 매수 시점 환율로 환산하는 것이 확정 규칙).
 */
data class Lot(
    val id: LotId,
    val userId: UserId,
    val symbol: Symbol,
    val boughtQuantity: Quantity,
    val remainingQuantity: Quantity,
    val unitCost: Money,
    val fxAtBuy: ExchangeRate?,
    val boughtAt: Instant,
    val origin: BuyOrigin,
) {
    init {
        if (boughtQuantity.isZero) throw InvalidValueException("lot 수량은 0보다 커야 함")
        if (remainingQuantity.isGreaterThan(boughtQuantity))
            throw InvalidValueException("잔여 수량 $remainingQuantity 이 매수 수량 $boughtQuantity 보다 큼")
        if (unitCost.currency != symbol.market.currency)
            throw InvalidValueException(
                "${symbol.market} lot 의 매입 단가는 ${symbol.market.currency} 여야 함"
            )
        if (symbol.market != Market.KR) {
            val fx = fxAtBuy ?: throw InvalidValueException("해외 lot 은 매수 시점 환율이 있어야 함: $symbol")
            if (fx.from != unitCost.currency || fx.to != Currency.KRW)
                throw InvalidValueException(
                    "환율은 ${unitCost.currency}→KRW 여야 함: ${fx.from}→${fx.to}"
                )
        }
    }

    val isOpen: Boolean
        get() = !remainingQuantity.isZero

    /** 남은 수량의 매입 원가(현지 통화). */
    fun costBasis(): Money = unitCost.times(remainingQuantity)

    /**
     * 남은 수량의 매입 원가를 매수 시점 환율로 원화 환산함.
     * 국내는 그대로.
     */
    fun costBasisInKrw(): Money = fxAtBuy?.let { costBasis().convert(it) } ?: costBasis()

    fun heldFor(now: Instant): Duration = Duration.between(boughtAt, now)

    /** 정확히 [window] 만큼 보유했으면 "지난" 것으로 봄(168시간 경계는 노출액에서 빠짐). */
    fun isHeldAtLeast(window: Duration, now: Instant): Boolean = heldFor(now) >= window

    fun afterSelling(quantity: Quantity): Lot {
        if (quantity.isGreaterThan(remainingQuantity))
            throw InvalidValueException("매도 수량 $quantity 이 잔여 수량 $remainingQuantity 을 넘음")
        return copy(remainingQuantity = remainingQuantity.minus(quantity))
    }
}
