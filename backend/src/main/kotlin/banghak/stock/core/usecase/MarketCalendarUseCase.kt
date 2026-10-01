package banghak.stock.core.usecase

/**
 * 장 달력을 미리 받아 둠.
 * 차트의 세션 경계 나눔은 지난 날의 달력이 많이 필요한데 달력 호출은 초당 3회라 그때 받으면 느림.
 */
interface SyncMarketCalendarUseCase {
    /** 최근 거래일과 오늘·내일의 달력 중 없는 것을 받아 저장함. */
    fun syncRecent()
}
