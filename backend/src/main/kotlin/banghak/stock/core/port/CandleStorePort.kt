package banghak.stock.core.port

import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.Candle
import banghak.stock.core.domain.trading.CandleCoverage
import banghak.stock.core.domain.trading.CandleInterval
import java.time.Instant

/**
 * 봉 저장소(`candle`·`candle_coverage`).
 * 증권사가 주는 1분봉·일봉만 담고, 묶은 봉은 저장하지 않음.
 * 설치 공용 시세라 사용자 범위가 없음.
 */
interface CandleStorePort {
    fun coverage(symbol: Symbol, interval: CandleInterval): CandleCoverage?

    /** 시작 시각이 [from] 이상 [before] 이하인 봉을 최신순으로 [limit] 개까지. */
    fun candles(
        symbol: Symbol,
        interval: CandleInterval,
        from: Instant,
        before: Instant,
        limit: Int,
    ): List<Candle>

    /**
     * 받은 봉과 보유 구간을 한 트랜잭션으로 저장함.
     * 같은 시각의 봉은 새 값으로 덮어씀(진행 중이던 봉의 보정).
     * [fetchedAt] 기준으로 아직 끝나지 않은 봉은 진행 중으로 표시함.
     */
    fun save(candles: List<Candle>, coverage: CandleCoverage?, fetchedAt: Instant)

    /**
     * [interval] 봉 중 시작 시각이 [olderThan] 보다 이른 것을 지우고 보유 구간을 그만큼 줄임.
     *
     * @return 지운 봉 수
     */
    fun purge(interval: CandleInterval, olderThan: Instant): Int
}
