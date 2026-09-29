package banghak.stock.core.port

import banghak.stock.core.domain.market.ListingBoard
import banghak.stock.core.domain.market.StockProfile
import banghak.stock.core.domain.market.StockWarning
import banghak.stock.core.domain.market.Symbol

/**
 * 증권사의 종목 정보 포트.
 * 공용 정보라 admin 의 토스 키로 부름.
 * 실패는 [banghak.stock.core.domain.error.MarketDataUnavailableException] 으로 올림.
 */
interface StockCatalogPort {
    /**
     * 한 상장 시장의 거래 가능 종목 전체.
     * 증권사가 하루 한 번 갱신함.
     */
    fun listedSymbols(board: ListingBoard): List<Symbol>

    /**
     * 종목 기본 정보 여러 건.
     * 한 번에 [MAX_SYMBOLS] 개까지.
     */
    fun profiles(symbols: List<Symbol>): List<StockProfile>

    /**
     * 지금 걸려 있는 매수 유의사항.
     * 없으면 빈 목록이며, VI 는 수 초 안에 반영됨.
     */
    fun warnings(symbol: Symbol): List<StockWarning>

    companion object {
        const val MAX_SYMBOLS = 200
    }
}
