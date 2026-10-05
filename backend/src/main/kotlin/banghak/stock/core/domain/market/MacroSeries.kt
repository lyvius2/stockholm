package banghak.stock.core.domain.market

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 거시 지표 시계열(FRED).
 * 지수 종가는 1영업일 지연됨.
 */
enum class MacroSeries(val fredId: String) {
    SP500("SP500"),
    DJIA("DJIA"),
    NASDAQ_COMPOSITE("NASDAQCOM"),
    NIKKEI225("NIKKEI225"),
}

/**
 * 시계열의 하루 관측값.
 * 값이 없는 날(휴장)은 오지 않음.
 */
data class MacroObservation(val date: LocalDate, val value: BigDecimal)
