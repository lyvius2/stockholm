package banghak.stock.core.domain.market

import java.time.Instant

/**
 * 종목 경고 플래그의 현재값.
 * 종목 상태 칩과 자동 매수 제외 필터가 같은 값을 봄.
 * VI 는 수 초 안에 바뀌므로 짧게만 캐시할 것.
 * null 은 출처가 없어 모르는 값임(토스는 관리종목을 주지 않고, 미국 종목의 거래정지도 주지 않음).
 * [hasUnknownWarning] 은 증권사가 새로 더한, 뜻을 모르는 유의사항이 걸려 있다는 뜻임.
 */
data class StockFlags(
    val symbol: Symbol,
    val isInvestmentWarning: Boolean,
    val isInvestmentRisk: Boolean,
    val isOverheated: Boolean,
    val isLiquidationTrading: Boolean,
    val isViStatic: Boolean,
    val isViDynamic: Boolean,
    val hasUnknownWarning: Boolean,
    val isTradingHalted: Boolean?,
    val isAdministrative: Boolean?,
    val asOf: Instant,
) {
    /**
     * 모르는 값이 하나라도 있음.
     * 자동 매수 제외 필터는 이 값이 true 면 제외할 것(모름을 안전으로 보지 않음).
     */
    val hasUnknownState: Boolean
        get() = hasUnknownWarning || isTradingHalted == null || isAdministrative == null

    companion object {
        /**
         * 매수 유의사항과 국내 거래 상태를 합쳐 만듦.
         * 거래정지는 KRX·NXT 어느 한쪽이라도 멈췄으면 정지로 봄(안전한 쪽).
         */
        fun of(
            symbol: Symbol,
            warnings: List<StockWarning>,
            krDetail: KrTradingDetail?,
            asOf: Instant,
        ): StockFlags {
            val types = warnings.map { it.type }.toSet()
            return StockFlags(
                symbol = symbol,
                isInvestmentWarning = StockWarningType.INVESTMENT_WARNING in types,
                isInvestmentRisk = StockWarningType.INVESTMENT_RISK in types,
                isOverheated = StockWarningType.OVERHEATED in types,
                isLiquidationTrading =
                    StockWarningType.LIQUIDATION_TRADING in types ||
                        krDetail?.isLiquidationTrading == true,
                isViStatic = types.any { it in STATIC_VI },
                isViDynamic = types.any { it in DYNAMIC_VI },
                hasUnknownWarning = StockWarningType.UNKNOWN in types,
                isTradingHalted = krDetail?.let(::haltedOf),
                isAdministrative = null,
                asOf = asOf,
            )
        }

        // NXT 미지원 종목의 NXT 정지 값은 규격상 null 이라 모름이 아님.
        // NXT 지원 종목인데 값이 없을 때만 모름으로 둠
        private fun haltedOf(detail: KrTradingDetail): Boolean? =
            when {
                detail.isKrxSuspended || detail.isNxtSuspended == true -> true
                detail.isNxtSupported && detail.isNxtSuspended == null -> null
                else -> false
            }

        private val STATIC_VI =
            setOf(StockWarningType.VI_STATIC, StockWarningType.VI_STATIC_AND_DYNAMIC)
        private val DYNAMIC_VI =
            setOf(StockWarningType.VI_DYNAMIC, StockWarningType.VI_STATIC_AND_DYNAMIC)
    }
}
