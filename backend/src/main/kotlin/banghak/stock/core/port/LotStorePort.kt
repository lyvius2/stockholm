package banghak.stock.core.port

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.portfolio.Lot

/**
 * lot 저장소.
 * 모든 조회는 사용자 범위 안에서만 동작함.
 */
interface LotStorePort {
    /** 이 사용자·시장의 미청산 lot. */
    fun openLots(userId: UserId, market: Market): List<Lot>
}
