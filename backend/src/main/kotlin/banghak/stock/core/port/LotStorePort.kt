package banghak.stock.core.port

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.portfolio.Lot
import banghak.stock.core.domain.portfolio.LotDisposal
import java.time.Instant

/**
 * lot 저장소.
 * 모든 조회는 사용자 범위 안에서만 동작함.
 */
interface LotStorePort {
    /** 이 사용자·시장의 미청산 lot. */
    fun openLots(userId: UserId, market: Market): List<Lot>

    /**
     * 새 lot 을 남김.
     * [brokerOrderId] 는 매수 주문(기초 lot 이면 null).
     */
    fun saveOpened(lot: Lot, brokerOrderId: String?)

    /**
     * 매도로 잔여 수량이 줄어든 lot 을 남김.
     * 잔여가 0 이면 [at] 에 청산됨.
     */
    fun saveReduced(lots: List<Lot>, at: Instant)

    fun saveDisposals(userId: UserId, symbol: Symbol, disposals: List<LotDisposal>, at: Instant)
}
