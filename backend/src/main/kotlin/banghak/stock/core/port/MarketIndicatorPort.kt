package banghak.stock.core.port

import banghak.stock.core.domain.market.IndicatorQuote
import banghak.stock.core.domain.market.MarketIndicator

/**
 * 시장 지표(국내 지수·국채) 포트.
 * 계좌와 무관한 공용 정보라 admin 의 토스 키로 받음.
 * 실패는 [banghak.stock.core.domain.error.MarketDataUnavailableException] 으로 올림.
 */
interface MarketIndicatorPort {
    /** 값을 받지 못한 지표는 결과에서 빠짐. */
    fun indicatorQuotes(indicators: List<MarketIndicator>): List<IndicatorQuote>
}
