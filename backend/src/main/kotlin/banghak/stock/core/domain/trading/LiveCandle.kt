package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 실시간 체결로 만드는 진행 중인 1분봉.
 * 화면이 마지막 봉을 움직이는 데만 씀.
 * 시세 채널은 체결이 빠질 수 있어 거래량·고가·저가가 실제보다 작을 수 있으므로 저장하지 않고, 닫힌 봉의 정확한 값은 증권사 봉 조회로 다시 받음.
 */
data class LiveCandle(val candle: Candle) {
    /**
     * 체결 하나를 반영한 결과.
     * [closed] 는 이 체결로 분이 넘어가 닫힌 직전 봉이며, 넘어가지 않았으면 null 임.
     */
    data class Step(val current: LiveCandle, val closed: Candle?)

    /**
     * 같은 분의 체결은 봉에 합치고, 다음 분 이후의 체결은 새 봉을 시작함.
     * 이미 지난 분의 체결(늦게 온 것)은 버림.
     * 정확히 분이 바뀌는 시각(00초)의 체결은 새 봉에 들어감.
     */
    fun accept(tick: TradeTick): Step {
        if (tick.symbol != candle.symbol) throw InvalidValueException("다른 종목의 체결임: ${tick.symbol}")
        val minute = minuteOf(tick.at)
        return when {
            minute.isBefore(candle.openTime) -> Step(this, closed = null)
            minute == candle.openTime -> Step(LiveCandle(merged(tick)), closed = null)
            else -> Step(start(tick), closed = candle)
        }
    }

    /**
     * [now] 가 이 봉의 1분 안이면 true.
     * 체결이 끊겨 멈춘 봉을 진행 중으로 보이지 않게 함.
     */
    fun isCurrentAt(now: Instant): Boolean = minuteOf(now) == candle.openTime

    private fun merged(tick: TradeTick): Candle =
        candle.copy(
            high = maxOf(candle.high, tick.price),
            low = minOf(candle.low, tick.price),
            close = tick.price,
            volume = candle.volume.plus(tick.volume),
        )

    companion object {
        fun start(tick: TradeTick): LiveCandle =
            LiveCandle(
                Candle(
                    tick.symbol,
                    CandleInterval.MINUTE_1,
                    minuteOf(tick.at),
                    open = tick.price,
                    high = tick.price,
                    low = tick.price,
                    close = tick.price,
                    volume = tick.volume,
                )
            )

        private fun minuteOf(at: Instant): Instant = at.truncatedTo(ChronoUnit.MINUTES)
    }
}
