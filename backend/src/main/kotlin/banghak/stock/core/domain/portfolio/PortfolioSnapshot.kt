package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.BrokerOrder
import banghak.stock.core.domain.trading.Fill
import java.time.Duration
import java.time.Instant

/**
 * 통화별 현금 매수 가능 금액.
 * 토스 buying-power 가 주는 값만 담음(D+1/D+2·담보비율은 규격에 없음).
 */
data class DepositBalance(val cashBuyingPower: Map<Currency, Money>, val asOf: Instant) {
    init {
        cashBuyingPower.entries
            .firstOrNull { (currency, money) -> money.currency != currency }
            ?.let {
                throw InvalidValueException("통화 ${it.key} 칸에 ${it.value.currency} 금액이 들어 있음")
            }
    }

    fun available(currency: Currency): Money = cashBuyingPower[currency] ?: Money.zero(currency)
}

/**
 * 가드레일이 보는 "그 순간의 계좌".
 * 전부 증권사에서 온 값에 로컬 lot 출처 태깅을 더한 것임.
 * 오래된 스냅샷으로는 주문하지 않음([isStale] 이면 가드레일이 거부).
 */
data class PortfolioSnapshot(
    val userId: UserId,
    val market: Market,
    val positions: List<Position>,
    val deposit: DepositBalance,
    val openOrders: List<BrokerOrder>,
    val todayFills: List<Fill>,
    val asOf: Instant,
) {
    init {
        positions
            .firstOrNull { it.symbol.market != market || it.userId != userId }
            ?.let {
                throw InvalidValueException(
                    "$userId/$market 스냅샷에 ${it.userId}/${it.symbol} 포지션이 섞임"
                )
            }
    }

    /**
     * 정확히 [maxAge] 만큼 지난 스냅샷은 아직 쓸 수 있음.
     * 그보다 오래되면 stale.
     */
    fun isStale(now: Instant, maxAge: Duration): Boolean = Duration.between(asOf, now) > maxAge

    fun position(symbol: Symbol): Position? = positions.firstOrNull { it.symbol == symbol }
}

/**
 * 손익.
 * 실현·평가 구분, 수수료·세금 반영, 해외는 원화 환산 병기.
 */
data class ProfitLoss(
    val realized: Money,
    val unrealized: Money,
    val fees: Money,
    val taxes: Money,
    val realizedKrw: Money?,
    val unrealizedKrw: Money?,
)
