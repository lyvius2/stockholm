package banghak.stock.core.usecase

import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.Candle

/**
 * 실시간 체결로 만든 진행 중인 1분봉.
 * 화면의 마지막 봉을 움직이는 용도이며 값은 근사임(체결이 빠질 수 있음).
 */
interface LookupLiveCandleUseCase {
    /**
     * @param symbol 종목
     * @return 지금 분의 진행 중인 봉. 이번 분에 받은 체결이 없으면 null
     */
    fun liveCandle(symbol: Symbol): Candle?
}
