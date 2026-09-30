package banghak.stock.engine.application.market

import banghak.stock.core.domain.trading.Candle
import banghak.stock.core.domain.trading.CandleRollup
import banghak.stock.core.domain.trading.Chart
import banghak.stock.core.domain.trading.ChartBar
import banghak.stock.core.domain.trading.MovingAverage
import banghak.stock.core.port.MarketDataPort
import banghak.stock.core.usecase.ChartQuery
import banghak.stock.core.usecase.LoadChartUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Instant
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 토스 1분봉·일봉을 받아 차트 봉으로 묶고 평균을 계산함.
 * 평균은 이번에 받은 봉으로만 계산하므로 과거 쪽 봉은 기간이 안 차 비어 있을 수 있음.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class ChartService(private val marketData: MarketDataPort) : LoadChartUseCase {
    override fun chart(query: ChartQuery): Chart {
        val fetched = fetch(query)
        val rolled = CandleRollup.rollUp(fetched.candles, query.resolution)
        // 더 과거가 남았으면 가장 오래된 묶음은 봉이 덜 찼을 수 있어 버리고 다음 조회가 다시 받음
        val complete = if (fetched.hasOlder) rolled.drop(1) else rolled
        val skipped = (complete.size - query.count).coerceAtLeast(0)
        val bars = complete.drop(skipped)
        return Chart(
            bars = bars,
            closeAverages =
                MovingAverage.PRICE_WINDOWS.associateWith {
                    MovingAverage.ofCloses(complete, it).drop(skipped)
                },
            volumeAverage = MovingAverage.ofVolumes(complete).drop(skipped),
            nextBefore = nextBeforeOf(query, bars, fetched.hasOlder || skipped > 0),
        )
    }

    // 묶음이 요청한 수보다 하나 더 모일 때까지 받음(가장 오래된 묶음은 버릴 수 있으므로)
    private fun fetch(query: ChartQuery): Fetched {
        val base = query.resolution.base
        val candles = mutableListOf<Candle>()
        var cursor = query.before
        var pages = 0
        while (pages++ < MAX_PAGES) {
            val page = marketData.candlePage(query.symbol, base, cursor, MarketDataPort.MAX_CANDLES)
            candles += page.candles
            cursor = page.nextBefore
            if (cursor == null || page.candles.isEmpty()) return Fetched(candles, hasOlder = false)
            if (bucketCount(query, candles) > query.count) break
        }
        return Fetched(candles, hasOlder = true)
    }

    private fun bucketCount(query: ChartQuery, candles: List<Candle>): Int =
        candles.map { query.resolution.bucketStart(it.openTime) }.distinct().size

    // 다음 조회는 돌려준 가장 오래된 봉보다 앞선 기준 봉부터 받음
    private fun nextBeforeOf(query: ChartQuery, bars: List<ChartBar>, hasOlder: Boolean): Instant? =
        if (hasOlder) bars.firstOrNull()?.openTime?.minus(query.resolution.base.length) else null

    private data class Fetched(val candles: List<Candle>, val hasOlder: Boolean)

    companion object {
        // 한 번의 조회에서 받는 기준 봉은 2000개까지(토스 차트 한도 초당 20회 안)
        private const val MAX_PAGES = 10
    }
}
