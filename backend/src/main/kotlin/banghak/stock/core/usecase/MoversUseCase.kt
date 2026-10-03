package banghak.stock.core.usecase

import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.MoverBoard

/**
 * 실시간 급등락 서랍(F11).
 * 정보 표시용이며 자동 주문의 트리거가 아님.
 */
interface LookupMoversUseCase {
    /**
     * 그 시장의 급등 5·급락 5.
     * 10초 안에 다시 부르면 같은 판을 돌려주고, 판이 바뀌면 직전 판과 비교한 순위 변동을 담음.
     *
     * @throws banghak.stock.core.domain.error.MarketDataUnavailableException 랭킹을 받지 못했고 직전 판도 없으면
     * 발생함
     */
    fun board(market: Market): MoverBoard
}
