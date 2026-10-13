package banghak.stock.core.domain.account

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.ChartResolution
import java.time.Instant

/**
 * 사용자 설정 키.
 * 값은 JSON 문자열로 두고 동기화됨(`UserSettingChanged`).
 */
enum class UserSettingKey {
    /** 직전에 네 영역에서 보던 종목(시작 종목 ⑴). */
    LAST_VIEWED_STOCK,
    /**
     * 기본 종목을 고를 시장(시작 종목 ⑶).
     * 기본값 국내.
     */
    DEFAULT_MARKET,
    /** 차트 보기(F3): 자세한 차트 토글과 모드별 마지막 기간 탭. */
    CHART_VIEW,
}

/**
 * 사용자 설정 한 건.
 * [value] 는 JSON 문자열임.
 */
data class UserSetting(
    val userId: UserId,
    val key: UserSettingKey,
    val value: String,
    val updatedAt: Instant,
) {
    init {
        if (value.isBlank()) throw InvalidValueException("설정 값이 비어 있음: $key")
    }
}

/** 직전에 보던 종목과 그때 시각. */
data class LastViewedStock(val symbol: Symbol, val viewedAt: Instant)

/**
 * 설정 값의 JSON 모양.
 * 숫자·따옴표 없는 단순 값이라 손으로 쓰고 읽음.
 */
object UserSettingCodec {
    fun lastViewedStock(value: LastViewedStock): String =
        """{"market":"${value.symbol.market.name}","code":"${value.symbol.code}","viewedAt":"${value.viewedAt}"}"""

    fun parseLastViewedStock(json: String): LastViewedStock =
        LastViewedStock(
            Symbol(Market.valueOf(field(json, "market")), field(json, "code")),
            Instant.parse(field(json, "viewedAt")),
        )

    fun defaultMarket(market: Market): String = """{"market":"${market.name}"}"""

    fun parseDefaultMarket(json: String): Market = Market.valueOf(field(json, "market"))

    fun chartView(view: ChartView): String =
        """{"isDetailed":${view.isDetailed},"simpleRange":"${view.simpleRange.name}","detailedResolution":"${view.detailedResolution.name}"}"""

    fun parseChartView(json: String): ChartView =
        ChartView(
            isDetailed = booleanField(json, "isDetailed"),
            simpleRange = enumField<SimpleChartRange>(json, "simpleRange"),
            detailedResolution = enumField<ChartResolution>(json, "detailedResolution"),
        )

    private inline fun <reified E : Enum<E>> enumField(json: String, name: String): E {
        val text = field(json, name)
        return enumValues<E>().firstOrNull { it.name == text }
            ?: throw InvalidValueException("설정 값의 $name 이 모르는 값임: $text")
    }

    private fun booleanField(json: String, name: String): Boolean =
        when (Regex("\"$name\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1)) {
            "true" -> true
            "false" -> false
            else -> throw InvalidValueException("설정 값에 $name 이 없음")
        }

    private fun field(json: String, name: String): String =
        Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
            ?: throw InvalidValueException("설정 값에 $name 이 없음")
}
