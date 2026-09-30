package banghak.stock.core.port

import banghak.stock.core.domain.market.Ranking
import banghak.stock.core.domain.market.RankingQuery

/**
 * 주식 랭킹 포트.
 * 계좌와 무관한 공용 정보라 admin 의 토스 키로 받음.
 * 실패는 [banghak.stock.core.domain.error.MarketDataUnavailableException] 으로 올림.
 * 랭킹은 정보 표시용이며 자동 주문의 트리거가 아님.
 */
interface RankingPort {
    fun ranking(query: RankingQuery): Ranking
}
