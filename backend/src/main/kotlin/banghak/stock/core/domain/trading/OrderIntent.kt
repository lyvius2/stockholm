package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import java.time.Instant

/**
 * 아직 증권사에 보내지 않은 주문 의도.
 * 화면·추천·자동화 어디서 왔든 이 타입으로 모인 뒤 가드레일을 지나야 함.
 * 생성 시 형태 불변식을 검사하므로 이 객체는 항상 규격에 맞는 주문임.
 */
data class OrderIntent(
    val userId: UserId,
    val symbol: Symbol,
    val side: OrderSide,
    val kind: OrderKind,
    val timeInForce: TimeInForce,
    val limitPrice: Money?,
    val quantity: Quantity?,
    val orderAmount: Money?,
    val origin: OrderOrigin,
    val trigger: OrderTrigger,
    val intendedAt: Instant,
) {
    init {
        when (kind) {
            OrderKind.LIMIT -> requireLimitShape()
            OrderKind.MARKET -> requireMarketShape()
        }
        requireTimeInForceShape()
        requireOriginMatchesTrigger()
    }

    val market: Market
        get() = symbol.market

    val isAutomatic: Boolean
        get() = trigger.isAutomatic

    /**
     * 주문 금액.
     * LIMIT 은 가격 × 수량, 시장가 매수는 주문 금액 그대로임.
     * 시장가 매도는 기준가가 있어야 계산할 수 있으므로 호출자가 현재가를 줌.
     */
    fun notional(referencePrice: Money? = null): Money =
        when {
            kind == OrderKind.LIMIT -> limitPrice!!.times(quantity!!)
            side == OrderSide.BUY -> orderAmount!!
            else ->
                (referencePrice ?: throw InvalidValueException("시장가 매도 금액에는 기준가가 필요함")).times(
                    quantity!!
                )
        }

    private fun requireLimitShape() {
        val price = limitPrice ?: throw InvalidValueException("지정가 주문에는 가격이 필요함")
        val shares = quantity ?: throw InvalidValueException("지정가 주문에는 수량이 필요함")
        if (orderAmount != null) throw InvalidValueException("지정가 주문에는 주문 금액을 쓰지 않음")
        requirePositivePrice(price)
        requireTradableQuantity(shares)
    }

    // 토스 규격: 시장가는 미국만, 소수점 수량은 시장가 매도에만, 금액 주문은 시장가 매수에만
    private fun requireMarketShape() {
        if (market != Market.US) throw InvalidValueException("시장가 주문은 미국 시장에서만 낼 수 있음")
        if (limitPrice != null) throw InvalidValueException("시장가 주문에는 가격을 쓰지 않음")
        when (side) {
            OrderSide.SELL -> {
                if (orderAmount != null) throw InvalidValueException("시장가 매도는 수량으로만 냄")
                requireTradableQuantity(quantity ?: throw InvalidValueException("시장가 매도에는 수량이 필요함"))
            }
            OrderSide.BUY -> {
                if (quantity != null) throw InvalidValueException("시장가 매수는 주문 금액으로만 냄")
                val amount = orderAmount ?: throw InvalidValueException("시장가 매수에는 주문 금액이 필요함")
                requirePositivePrice(amount)
            }
        }
    }

    private fun requireTimeInForceShape() {
        when (timeInForce) {
            TimeInForce.DAY -> Unit
            TimeInForce.CLS ->
                if (market != Market.US || kind != OrderKind.LIMIT)
                    throw InvalidValueException("CLS 는 미국 지정가 주문에만 쓸 수 있음")
            TimeInForce.OPG ->
                if (market != Market.KR) throw InvalidValueException("OPG 는 국내 주문에만 쓸 수 있음")
        }
    }

    // 자동 주문은 지정가만 냄.
    // 사람의 정정은 원주문 출처를 그대로 이어받으므로 출처를 묻지 않음
    private fun requireOriginMatchesTrigger() {
        val expected =
            when (trigger) {
                is ManualTrigger -> OrderOrigin.MANUAL
                is RecommendationTrigger -> OrderOrigin.AI_RECOMMENDED
                is AutoBuyTrigger -> OrderOrigin.AUTO_BUY
                is AutoSellTrigger -> OrderOrigin.AUTO_SELL
                is ManualAmendTrigger -> origin
            }
        if (origin != expected)
            throw InvalidValueException("출처 $origin 은 트리거 ${trigger::class.simpleName} 와 맞지 않음")
        if (trigger is AutoBuyTrigger && side != OrderSide.BUY)
            throw InvalidValueException("자동 매수 트리거는 매수 주문에만 씀")
        if (trigger is AutoSellTrigger && side != OrderSide.SELL)
            throw InvalidValueException("자동 매도 트리거는 매도 주문에만 씀")
        if (trigger.isAutomatic && kind != OrderKind.LIMIT)
            throw InvalidValueException("자동 주문은 지정가만 낼 수 있음")
    }

    private fun requirePositivePrice(money: Money) {
        if (money.currency != market.currency)
            throw InvalidValueException("$market 주문 금액은 ${market.currency} 여야 함: ${money.currency}")
        if (!money.isPositive) throw InvalidValueException("금액은 0보다 커야 함: $money")
    }

    private fun requireTradableQuantity(shares: Quantity) {
        if (shares.isZero) throw InvalidValueException("수량은 0보다 커야 함")
        if (!shares.isValidFor(market))
            throw InvalidValueException("$market 수량은 소수 ${market.quantityScale}자리까지만 허용함: $shares")
    }
}
