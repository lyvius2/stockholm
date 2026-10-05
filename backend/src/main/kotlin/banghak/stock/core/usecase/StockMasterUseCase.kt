package banghak.stock.core.usecase

import banghak.stock.core.domain.market.StockQuery
import banghak.stock.core.domain.market.StockSummary
import banghak.stock.core.domain.market.Symbol

/**
 * 종목 마스터 동기화.
 * 증권사의 상장 종목 전체를 받아 마스터를 통째로 갱신함.
 */
interface SyncStockMasterUseCase {
    /**
     * 마지막 동기화가 오늘 갱신 기준 시각보다 앞서면 동기화함.
     * 설치가 끝나지 않았거나 admin 의 토스 키가 없으면 건너뜀.
     */
    fun syncIfStale()
}

/** 종목 마스터 조회·검색(F4 종목 선택·상단 바 종목명). */
interface LookupStockUseCase {
    /** 상장 중이 아니면 null. */
    fun find(symbol: Symbol): StockSummary?

    fun search(query: StockQuery): List<StockSummary>
}
