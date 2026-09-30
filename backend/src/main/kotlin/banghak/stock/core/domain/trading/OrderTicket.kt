package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.money.Percent
import java.time.Instant
import java.time.LocalDate

/**
 * 당일 상한가·하한가.
 * 미국처럼 가격 제한이 없는 시장은 둘 다 없음.
 */
data class PriceLimits(
    val symbol: Symbol,
    val upper: Money?,
    val lower: Money?,
    val asOf: Instant,
) {
    init {
        listOfNotNull(upper, lower).forEach {
            if (it.currency != symbol.market.currency)
                throw InvalidValueException("$symbol 상·하한가 통화가 시장 통화와 다름: ${it.currency}")
        }
    }

    /**
     * 가격 제한이 없거나 [price] 가 하한가 이상 상한가 이하이면 true.
     * 경계값은 포함함.
     */
    fun allows(price: Money): Boolean =
        (upper == null || price <= upper) && (lower == null || price >= lower)
}

/**
 * 계좌의 시장별 매매 수수료율.
 * 적용 기간이 없으면(null) 그쪽으로 기한이 없음.
 */
data class CommissionRate(
    val market: Market,
    val rate: Percent,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
) {
    /** 시작일과 종료일을 포함함. */
    fun isEffectiveOn(date: LocalDate): Boolean =
        (startDate == null || !date.isBefore(startDate)) &&
            (endDate == null || !date.isAfter(endDate))
}

/**
 * 주문 모달이 한 종목에서 보여 주는 주문 가능 정보.
 * [priceLimits] 와 [commissionRate] 는 받지 못하면 없음(주문을 막지 않는 참고 정보).
 */
data class OrderTicket(
    val symbol: Symbol,
    val buyingPower: Money,
    val sellableQuantity: Quantity,
    val priceLimits: PriceLimits?,
    val commissionRate: Percent?,
)
