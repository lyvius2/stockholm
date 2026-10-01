package banghak.stock.engine.application.market

import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.CandleCoverage
import banghak.stock.core.domain.trading.CandleInterval
import banghak.stock.core.domain.trading.CandlePage
import banghak.stock.core.port.CandleStorePort
import banghak.stock.core.port.MarketDataPort
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 봉을 저장소에서 먼저 찾고 없는 구간만 토스에서 받아 저장함(cache-aside).
 * 가장 최근 쪽은 진행 중인 봉이 있어 조회할 때마다 토스에서 한 페이지를 다시 받아 덮어씀.
 * 토스를 받지 못하면 저장된 봉을 주고 지연임을 알림.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class CandleHistory(
    private val marketData: MarketDataPort,
    private val store: CandleStorePort,
    private val clock: Clock,
) {
    /** [isDelayed] 가 true 면 토스를 받지 못해 저장된 봉만 준 것임. */
    data class Result(val page: CandlePage, val isDelayed: Boolean)

    // 같은 종목·봉 단위의 보유 구간을 두 요청이 동시에 고치면 받지 않은 구간을 가진 것으로 적을 수 있어 직렬화함
    private val seriesLocks = ConcurrentHashMap<Pair<Symbol, CandleInterval>, ReentrantLock>()

    // 보존 기간 정리가 봉을 지우는 동안 옛 보유 구간으로 다시 저장하지 않게 조회 전체와 배타로 둠
    private val purgeLock = ReentrantReadWriteLock()

    // 가장 최근 쪽을 토스에서 마지막으로 받은 시각(종목·봉 단위별)
    private val refreshedAt = ConcurrentHashMap<Pair<Symbol, CandleInterval>, Instant>()

    /**
     * 시작 시각이 [before] 이하인 봉을 최신순으로 [count] 개까지(null 이면 가장 최근부터).
     * 토스에서 받는 구간은 한 번에 200개까지라 [count] 보다 적게 올 수 있으며, 이어 받을 위치는 돌려준 페이지의 `nextBefore` 임.
     * [maxAge] 는 가장 최근 쪽을 다시 받지 않고 저장된 것으로 때워도 되는 시간임(0이면 매번 다시 받음).
     * 전일 종가처럼 하루에 한 번 바뀌는 값은 길게 줘 보유 종목마다 토스를 부르지 않게 함.
     */
    fun page(
        symbol: Symbol,
        interval: CandleInterval,
        before: Instant?,
        count: Int,
        maxAge: Duration = Duration.ZERO,
    ): Result = purgeLock.read {
        seriesLocks
            .computeIfAbsent(symbol to interval) { ReentrantLock() }
            .withLock {
                val coverage = store.coverage(symbol, interval)
                when {
                    before == null && coverage != null && isFreshEnough(symbol, interval, maxAge) ->
                        fresh(stored(coverage, coverage.to, count))
                    before == null -> newest(symbol, interval, coverage, count)
                    coverage == null -> fresh(uncovered(symbol, interval, before, count))
                    coverage.contains(before) -> fresh(stored(coverage, before, count))
                    before.isAfter(coverage.to) -> newest(symbol, interval, coverage, count)
                    coverage.reachedStart -> fresh(CandlePage(emptyList(), null))
                    before == coverage.olderCursor() -> fresh(older(coverage, before, count))
                    else -> fresh(detached(symbol, interval, before, count))
                }
            }
    }

    /** 보존 기간이 지난 1분봉을 지움. */
    fun purgeExpired(): Int = purgeLock.write {
        store.purge(
            CandleInterval.MINUTE_1,
            clock.instant().minus(CandleCoverage.MINUTE_RETENTION),
        )
    }

    private fun newest(
        symbol: Symbol,
        interval: CandleInterval,
        coverage: CandleCoverage?,
        count: Int,
    ): Result {
        val page =
            try {
                marketData.candlePage(symbol, interval, null, MarketDataPort.MAX_CANDLES)
            } catch (e: MarketDataUnavailableException) {
                if (coverage == null) throw e
                log.warn("최근 봉을 받지 못해 저장된 봉을 줌({})", e::class.simpleName)
                return Result(stored(coverage, coverage.to, count), isDelayed = true)
            }
        val merged = coverage?.mergeNewest(page) ?: CandleCoverage.of(symbol, interval, page)
        store.save(page.candles, merged, clock.instant())
        refreshedAt[symbol to interval] = clock.instant()
        if (merged == null) return fresh(CandlePage(emptyList(), null))
        return fresh(stored(merged, merged.to, count))
    }

    private fun stored(coverage: CandleCoverage, before: Instant, count: Int): CandlePage {
        val rows =
            store.candles(coverage.symbol, coverage.interval, coverage.from, before, count + 1)
        val next = if (rows.size > count) rows[count].openTime else coverage.olderCursor()
        return CandlePage(rows.take(count), next)
    }

    private fun older(coverage: CandleCoverage, before: Instant, count: Int): CandlePage {
        val page = fetch(coverage.symbol, coverage.interval, before, count)
        val merged = coverage.mergeOlder(page)
        store.save(page.candles, merged, clock.instant())
        return CandlePage(page.candles, merged.olderCursor())
    }

    // 저장된 것이 없으면 받은 페이지가 첫 보유 구간이 됨
    private fun uncovered(
        symbol: Symbol,
        interval: CandleInterval,
        before: Instant,
        count: Int,
    ): CandlePage {
        val page = fetch(symbol, interval, before, count)
        val coverage = CandleCoverage.of(symbol, interval, page)
        store.save(page.candles, coverage, clock.instant())
        return CandlePage(page.candles, coverage?.olderCursor())
    }

    // 보유 구간과 떨어진 과거는 봉만 저장하고 구간은 늘리지 않음(사이를 받지 않았음)
    private fun detached(
        symbol: Symbol,
        interval: CandleInterval,
        before: Instant,
        count: Int,
    ): CandlePage {
        val page = fetch(symbol, interval, before, count)
        store.save(page.candles, null, clock.instant())
        return page
    }

    private fun fetch(
        symbol: Symbol,
        interval: CandleInterval,
        before: Instant,
        count: Int,
    ): CandlePage =
        marketData.candlePage(symbol, interval, before, minOf(count, MarketDataPort.MAX_CANDLES))

    private fun isFreshEnough(symbol: Symbol, interval: CandleInterval, maxAge: Duration): Boolean {
        val last = refreshedAt[symbol to interval] ?: return false
        return Duration.between(last, clock.instant()) < maxAge
    }

    private fun fresh(page: CandlePage) = Result(page, isDelayed = false)

    companion object {
        private val log = LoggerFactory.getLogger(CandleHistory::class.java)
    }
}
