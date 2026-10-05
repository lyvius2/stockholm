package banghak.stock.core.domain.market

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.money.Percent
import banghak.stock.core.domain.money.RoundingRules
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * 상단 바 지수 티커(F23)의 지수.
 * [market] 은 어느 장 세트에 속하는지이며 NIKKEI 225 는 한국 장 세트에 묶임.
 */
enum class IndexCode(val displayName: String, val market: Market) {
    KOSPI("KOSPI", Market.KR),
    KOSDAQ("KOSDAQ", Market.KR),
    NIKKEI225("NIKKEI 225", Market.KR),
    DJIA("DJIA", Market.US),
    NASDAQ("NASDAQ", Market.US),
    SP500("S&P 500", Market.US);

    companion object {
        fun setOf(market: Market): List<IndexCode> = entries.filter { it.market == market }
    }
}

/**
 * 지수 하나의 값.
 * [change]·[changeRatio] 는 전일 종가 대비이며 전일 종가를 모르면 없음.
 * [isClosed] 가 true 면 장 밖의 종가임.
 * [proxy] 는 지수 대신 쓴 ETF 티커(미국 장중)이며 화면이 이름 뒤에 표기함.
 */
data class IndexQuote(
    val code: IndexCode,
    val value: BigDecimal,
    val change: BigDecimal?,
    val changeRatio: Percent?,
    val asOf: Instant,
    val isClosed: Boolean,
    val proxy: String?,
    val source: String,
) {
    companion object {
        /** 전일 종가가 0이면 등락률을 내지 않음. */
        fun of(
            code: IndexCode,
            value: BigDecimal,
            previousClose: BigDecimal?,
            asOf: Instant,
            isClosed: Boolean,
            proxy: String?,
            source: String,
        ): IndexQuote {
            val change = previousClose?.let { value - it }
            val ratio =
                if (previousClose == null || previousClose.signum() == 0) null
                else
                    Percent(
                        change!!.divide(
                            previousClose,
                            RoundingRules.RATIO_SCALE,
                            RoundingRules.MONEY,
                        )
                    )
            return IndexQuote(code, value, change, ratio, asOf, isClosed, proxy, source)
        }
    }
}

/** 지금 보여 줄 세트가 장중인지, 개장 전인지, 종가인지. */
enum class IndexSetState {
    OPEN,
    PRE,
    CLOSE,
}

data class IndexSetChoice(val market: Market, val state: IndexSetState) {
    val codes: List<IndexCode>
        get() = IndexCode.setOf(market)
}

/**
 * 세트 선택 규칙.
 * 한국 정규장 중이면 한국, 미국 정규장 중이면 미국, 미국 개장 30분 전부터 미국(프리), 한국 개장 30분 전부터 한국(종가), 그 밖에는 가장 최근에 닫힌 시장의 종가.
 * 정규장 창은 앞뒤 날까지 넘겨 받아 날짜를 넘는 미국 장도 맞춤.
 */
object IndexSetRule {
    val PRE_OPEN: Duration = Duration.ofMinutes(30)

    fun choose(
        now: Instant,
        krRegular: List<SessionWindow>,
        usRegular: List<SessionWindow>,
    ): IndexSetChoice {
        requireRegular(krRegular + usRegular)
        if (krRegular.any { it.contains(now) }) return IndexSetChoice(Market.KR, IndexSetState.OPEN)
        if (usRegular.any { it.contains(now) }) return IndexSetChoice(Market.US, IndexSetState.OPEN)
        if (opensSoon(now, usRegular)) return IndexSetChoice(Market.US, IndexSetState.PRE)
        if (opensSoon(now, krRegular)) return IndexSetChoice(Market.KR, IndexSetState.CLOSE)
        val lastKr = lastEnded(now, krRegular)
        val lastUs = lastEnded(now, usRegular)
        val market =
            when {
                lastKr == null && lastUs == null -> Market.KR
                lastKr == null -> Market.US
                lastUs == null -> Market.KR
                lastUs.isAfter(lastKr) -> Market.US
                else -> Market.KR
            }
        return IndexSetChoice(market, IndexSetState.CLOSE)
    }

    // 개장 정확히 30분 전부터 포함
    private fun opensSoon(now: Instant, windows: List<SessionWindow>): Boolean = windows.any {
        !now.isBefore(it.start.minus(PRE_OPEN)) && now.isBefore(it.start)
    }

    private fun lastEnded(now: Instant, windows: List<SessionWindow>): Instant? =
        windows.map { it.end }.filter { !it.isAfter(now) }.maxOrNull()

    private fun requireRegular(windows: List<SessionWindow>) {
        windows
            .firstOrNull { it.session != MarketSession.REGULAR }
            ?.let {
                throw InvalidValueException("세트 선택은 정규장 창만 받음: ${it.session}")
            }
    }
}

/**
 * 지수 하나의 출처 상태.
 * [UNCONFIGURED] 는 출처 키(FRED)가 등록되지 않아 받을 수 없는 것이고, [DELAYED] 는 받지 못해 마지막 값을 쓴 것임.
 */
enum class IndexSourceState {
    FRESH,
    DELAYED,
    UNCONFIGURED,
}

/**
 * 티커의 한 자리.
 * [quote] 는 마지막으로 아는 값이며 한 번도 받지 못했으면 없음.
 */
data class IndexEntry(val code: IndexCode, val quote: IndexQuote?, val state: IndexSourceState)

/**
 * 지금 보여 줄 지수 세트와 값.
 * 세트의 지수마다 자리가 하나씩 있어 값이 없는 지수도 상태로 보임.
 * [isDelayed] 가 true 면 하나라도 새로 받지 못해 마지막 값을 썼음(출처 미설정은 지연이 아님).
 */
data class IndexTicker(
    val choice: IndexSetChoice,
    val entries: List<IndexEntry>,
    val asOf: Instant,
) {
    init {
        if (entries.map { it.code } != choice.codes)
            throw InvalidValueException("티커 자리는 세트의 지수 순서와 같아야 함: ${entries.map { it.code }}")
    }

    val isDelayed: Boolean
        get() = entries.any { it.state == IndexSourceState.DELAYED }

    val quotes: List<IndexQuote>
        get() = entries.mapNotNull { it.quote }
}
