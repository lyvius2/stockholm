package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.Symbol
import java.time.Duration
import java.time.Instant

/**
 * 저장해 둔 봉이 빠짐없이 이어지는 구간.
 * 시작 시각이 [from] 이상 [to] 이하인 봉은 증권사에 있는 것을 모두 갖고 있음.
 * 장이 닫힌 시간에는 봉이 없어 저장된 봉만 봐서는 빠진 구간을 알 수 없으므로 받은 범위를 따로 적어 둠.
 * [reachedStart] 가 true 면 [from] 보다 과거의 봉은 증권사에도 없음.
 */
data class CandleCoverage(
    val symbol: Symbol,
    val interval: CandleInterval,
    val from: Instant,
    val to: Instant,
    val reachedStart: Boolean,
) {
    init {
        if (from.isAfter(to)) throw InvalidValueException("봉 보유 구간의 시작이 끝보다 늦음: $from > $to")
    }

    /** 경계(정확히 [from]·[to])를 포함함. */
    fun contains(openTime: Instant): Boolean = !openTime.isBefore(from) && !openTime.isAfter(to)

    /**
     * 이 구간 바로 앞(더 과거)을 이어 받을 위치.
     * 더 받을 과거가 없으면 null.
     */
    fun olderCursor(): Instant? = if (reachedStart) null else from.minus(interval.length)

    /**
     * 가장 최근 쪽에서 새로 받은 페이지를 합침.
     * 새 페이지가 이 구간에 닿거나 겹치면(또는 증권사 과거 전체면) 구간을 이어 늘리고, 사이가 비면 새 페이지만 남김.
     * 사이가 빈 채로 이으면 그 구간을 가진 것으로 잘못 알게 됨.
     */
    fun mergeNewest(page: CandlePage): CandleCoverage {
        val fetched = of(symbol, interval, page) ?: return this
        val touches = fetched.reachedStart || !fetched.from.isAfter(to.plus(interval.length))
        if (!touches) return fetched
        return copy(
            from = minOf(from, fetched.from),
            to = maxOf(to, fetched.to),
            reachedStart = reachedStart || fetched.reachedStart,
        )
    }

    /**
     * [olderCursor] 로 받은 더 과거 페이지를 합침.
     * 빈 페이지면 더 받을 과거가 없는 것임.
     */
    fun mergeOlder(page: CandlePage): CandleCoverage {
        val fetched = of(symbol, interval, page) ?: return copy(reachedStart = true)
        return copy(from = minOf(from, fetched.from), reachedStart = fetched.reachedStart)
    }

    /**
     * 보존 기간이 지나 [cutoff] 이전 봉을 지웠을 때의 구간.
     * 남는 봉이 없으면 null.
     */
    fun trimmedTo(cutoff: Instant): CandleCoverage? =
        when {
            to.isBefore(cutoff) -> null
            from.isBefore(cutoff) -> copy(from = cutoff, reachedStart = false)
            else -> this
        }

    companion object {
        /**
         * 페이지 하나가 덮는 구간.
         * 봉이 없으면 null.
         */
        fun of(symbol: Symbol, interval: CandleInterval, page: CandlePage): CandleCoverage? {
            if (page.candles.isEmpty()) return null
            return CandleCoverage(
                symbol,
                interval,
                page.candles.minOf { it.openTime },
                page.candles.maxOf { it.openTime },
                reachedStart = page.nextBefore == null,
            )
        }

        /**
         * 1분봉 보존 기간.
         * 일봉은 지우지 않음.
         */
        val MINUTE_RETENTION: Duration = Duration.ofDays(90)
    }
}
