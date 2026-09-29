package banghak.stock.core.usecase

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
