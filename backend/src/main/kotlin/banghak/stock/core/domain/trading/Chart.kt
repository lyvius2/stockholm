package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.money.RoundingRules
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 차트 봉 단위.
 * 토스는 1분봉과 일봉만 주므로 나머지는 [base] 봉을 묶어 만듦.
 * 분 단위는 1분봉으로, 주·월·년은 일봉으로 만듦(1분봉 합은 일봉과 다름).
 */
enum class ChartResolution(val base: CandleInterval, private val minutes: Long?) {
    MINUTE_1(CandleInterval.MINUTE_1, 1),
    MINUTE_3(CandleInterval.MINUTE_1, 3),
    MINUTE_5(CandleInterval.MINUTE_1, 5),
    MINUTE_10(CandleInterval.MINUTE_1, 10),
    MINUTE_30(CandleInterval.MINUTE_1, 30),
    MINUTE_60(CandleInterval.MINUTE_1, 60),
    DAY(CandleInterval.DAY_1, null),
    WEEK(CandleInterval.DAY_1, null),
    MONTH(CandleInterval.DAY_1, null),
    YEAR(CandleInterval.DAY_1, null);

    /**
     * [openTime] 에 시작한 기준 봉이 들어갈 묶음의 시작 시각.
     * 분 단위는 시계에 맞춤(10분봉은 매시 00·10·20…분).
     * 프리·애프터·데이마켓까지 세션이 여럿이라 장 시작이 아니라 시계를 기준으로 함.
     * 세션을 구분하지 않으므로 경계가 묶음 중간에 오면 두 세션의 봉이 한 묶음에 섞임.
     * 60분봉만 해당함(미국 정규장 시작 09:30, 국내 정규장 종료 15:30).
     * 주는 월요일, 월은 1일, 년은 1월 1일에 시작함.
     */
    fun bucketStart(openTime: Instant): Instant {
        if (minutes != null) {
            val size = Duration.ofMinutes(minutes).seconds
            return Instant.ofEpochSecond(Math.floorDiv(openTime.epochSecond, size) * size)
        }
        val day = openTime.atZone(DAILY_ZONE).toLocalDate()
        val first: LocalDate =
            when (this) {
                WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                MONTH -> day.withDayOfMonth(1)
                YEAR -> day.withDayOfYear(1)
                else -> day
            }
        return first.atStartOfDay(DAILY_ZONE).toInstant()
    }

    companion object {
        // 토스 일봉 시각은 그 거래일 0시(국내 실측은 한국 시간).
        // TODO(실제 키 검증 때 미국 일봉 시각 확인): 미국 일봉도 한국 시간 0시로 온다고 보고 날짜를 한국 시간으로 읽음
        private val DAILY_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

/**
 * 차트에 그리는 봉 하나.
 * [openTime] 은 묶음의 시작 시각임.
 */
data class ChartBar(
    val symbol: Symbol,
    val resolution: ChartResolution,
    val openTime: Instant,
    val open: Money,
    val high: Money,
    val low: Money,
    val close: Money,
    val volume: Quantity,
)

/** 기준 봉을 차트 봉 단위로 묶음. */
object CandleRollup {
    /**
     * 시가는 묶음의 첫 봉, 종가는 마지막 봉, 고가·저가는 묶음 안의 최고·최저, 거래량은 합임.
     * 입력 순서는 상관없고 결과는 시각 오름차순임.
     * 봉이 없는 구간은 묶음을 만들지 않음.
     */
    fun rollUp(candles: List<Candle>, resolution: ChartResolution): List<ChartBar> {
        if (candles.isEmpty()) return emptyList()
        val symbol = candles.first().symbol
        candles.forEach {
            if (it.symbol != symbol) throw InvalidValueException("다른 종목의 봉이 섞임: ${it.symbol}")
            if (it.interval != resolution.base)
                throw InvalidValueException(
                    "$resolution 은 ${resolution.base} 봉으로 만듦: ${it.interval}"
                )
        }
        return candles
            .distinctBy { it.openTime }
            .sortedBy { it.openTime }
            .groupBy { resolution.bucketStart(it.openTime) }
            .map { (start, bucket) -> barOf(symbol, resolution, start, bucket) }
    }

    private fun barOf(
        symbol: Symbol,
        resolution: ChartResolution,
        start: Instant,
        bucket: List<Candle>,
    ) =
        ChartBar(
            symbol = symbol,
            resolution = resolution,
            openTime = start,
            open = bucket.first().open,
            high = bucket.maxOf { it.high },
            low = bucket.minOf { it.low },
            close = bucket.last().close,
            volume = bucket.fold(Quantity.ZERO) { sum, candle -> sum.plus(candle.volume) },
        )
}

/**
 * 단순 이동평균.
 * 결과는 입력과 같은 길이이고, 앞쪽에 봉이 [window] 개가 안 되는 자리는 비어 있음.
 */
object MovingAverage {
    /** 종가 이동평균 기간(봉 수). */
    val PRICE_WINDOWS: List<Int> = listOf(5, 20, 60, 120)

    /** 거래량 평균 기간(봉 수). */
    const val VOLUME_WINDOW = 20

    // 거래량 평균은 소수 둘째 자리까지 둠
    private const val VOLUME_SCALE = 2

    /** 종가 평균은 금액 반올림 규칙에 따라 통화 자릿수로 맞춤. */
    fun ofCloses(bars: List<ChartBar>, window: Int): List<Money?> {
        if (bars.isEmpty()) return emptyList()
        val currency = bars.first().close.currency
        return averages(bars.map { it.close.amount }, window) { sum ->
            Money.of(sum.divide(BigDecimal(window), currency.scale, RoundingRules.MONEY), currency)
        }
    }

    fun ofVolumes(bars: List<ChartBar>, window: Int = VOLUME_WINDOW): List<BigDecimal?> =
        averages(bars.map { it.volume.value }, window) { sum ->
            sum.divide(BigDecimal(window), VOLUME_SCALE, RoundingMode.HALF_EVEN)
        }

    // 창을 한 칸씩 밀며 합에서 빠지는 값을 빼고 들어오는 값을 더함
    private fun <T> averages(
        values: List<BigDecimal>,
        window: Int,
        average: (BigDecimal) -> T,
    ): List<T?> {
        if (window < 1) throw InvalidValueException("이동평균 기간은 1 이상: $window")
        var sum = BigDecimal.ZERO
        return values.mapIndexed { index, value ->
            sum += value
            if (index >= window) sum -= values[index - window]
            if (index >= window - 1) average(sum) else null
        }
    }
}

/**
 * 차트 한 화면 분량.
 * [bars] 는 시각 오름차순이고, 평균 목록은 [bars] 와 같은 길이·순서임.
 * [closeAverages] 의 키는 이동평균 기간(봉 수)임.
 * [nextBefore] 를 다음 조회에 넘기면 더 과거의 봉을 받고, null 이면 더 없음.
 * [isDelayed] 가 true 면 증권사를 받지 못해 저장해 둔 봉을 준 것이라 화면이 지연을 표시함.
 */
data class Chart(
    val bars: List<ChartBar>,
    val closeAverages: Map<Int, List<Money?>>,
    val volumeAverage: List<BigDecimal?>,
    val nextBefore: Instant?,
    val isDelayed: Boolean = false,
)
