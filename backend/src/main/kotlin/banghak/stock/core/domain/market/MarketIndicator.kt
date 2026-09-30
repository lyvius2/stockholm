package banghak.stock.core.domain.market

import java.math.BigDecimal
import java.time.Instant

/**
 * 토스가 주는 시장 지표.
 * 지수는 포인트, 국채는 수익률(%)임(3.25 = 3.25%).
 * 해외 지수는 없음.
 */
enum class MarketIndicator(val isIndex: Boolean) {
    KOSPI(isIndex = true),
    KOSDAQ(isIndex = true),
    KR_BOND_2Y(isIndex = false),
    KR_BOND_3Y(isIndex = false),
    KR_BOND_5Y(isIndex = false),
    KR_BOND_10Y(isIndex = false),
    KR_BOND_20Y(isIndex = false),
    KR_BOND_30Y(isIndex = false),
}

/**
 * 시장 지표의 현재 값.
 * 금액이 아니라 포인트·수익률이라 통화가 없음.
 * 토스가 시각을 주지 않으면 [asOf] 가 없음.
 * 등락은 주지 않아 전일 종가(지표 일봉)로 따로 계산해야 함.
 */
data class IndicatorQuote(val indicator: MarketIndicator, val value: BigDecimal, val asOf: Instant?)
