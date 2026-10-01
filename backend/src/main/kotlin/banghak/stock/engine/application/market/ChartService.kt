package banghak.stock.engine.application.market

import banghak.stock.core.domain.trading.Candle
import banghak.stock.core.domain.trading.CandleRollup
import banghak.stock.core.domain.trading.Chart
import banghak.stock.core.domain.trading.ChartBar
import banghak.stock.core.domain.trading.MovingAverage
import banghak.stock.core.usecase.ChartQuery
import banghak.stock.core.usecase.LoadChartUseCase
import banghak.stock.core.usecase.PurgeCandlesUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Instant
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 1분봉·일봉을 차트 봉으로 묶고 평균을 계산함.
 * 봉은 저장소에 있으면 거기서, 없으면 토스에서 받음([CandleHistory]).
 * 평균은 이번에 읽은 봉으로만 계산하므로 과거 쪽 봉은 기간이 안 차 비어 있을 수 있음.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class ChartService(private val history: CandleHistory) : LoadChartUseCase, PurgeCandlesUseCase {
    override fun purgeExpired() {
        history.purgeExpired()
    }

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
            isDelayed = fetched.isDelayed,
        )
    }

    // 묶음이 요청한 수보다 하나 더 모일 때까지 받음(가장 오래된 묶음은 버릴 수 있으므로)
    private fun fetch(query: ChartQuery): Fetched {
        val base = query.resolution.base
        val candles = mutableListOf<Candle>()
        var cursor = query.before
        var isDelayed = false
        var pages = 0
        while (pages++ < MAX_PAGES) {
            val result = history.page(query.symbol, base, cursor, PAGE_SIZE)
            val page = result.page
            isDelayed = isDelayed || result.isDelayed
            candles += page.candles
            cursor = page.nextBefore
            if (cursor == null || page.candles.isEmpty())
                return Fetched(candles, hasOlder = false, isDelayed)
            if (bucketCount(query, candles) > query.count) break
        }
        return Fetched(candles, hasOlder = true, isDelayed)
    }

    private fun bucketCount(query: ChartQuery, candles: List<Candle>): Int =
        candles.map { query.resolution.bucketStart(it.openTime) }.distinct().size

    // 다음 조회는 돌려준 가장 오래된 봉보다 앞선 기준 봉부터 받음
    private fun nextBeforeOf(query: ChartQuery, bars: List<ChartBar>, hasOlder: Boolean): Instant? =
        if (hasOlder) bars.firstOrNull()?.openTime?.minus(query.resolution.base.length) else null

    private data class Fetched(
        val candles: List<Candle>,
        val hasOlder: Boolean,
        val isDelayed: Boolean,
    )

    companion object {
        // 저장된 구간은 한 번에 이만큼 읽고, 토스에서 받는 구간은 200개씩 옴
        private const val PAGE_SIZE = 2000

        // 저장된 봉만으로 60분봉 500개(1분봉 3만 개)를 채울 수 있고, 토스 호출은 많아야 20회(초당 한도)임
        private const val MAX_PAGES = 20
    }
}
