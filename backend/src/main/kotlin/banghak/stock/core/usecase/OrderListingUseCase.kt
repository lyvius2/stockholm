package banghak.stock.core.usecase

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.OrderListing

/**
 * 3번 영역 미체결·오늘 체결 탭(F14).
 * 로컬 기록을 읽을 뿐 증권사를 부르지 않음(기록은 실시간 주문 채널과 재동기가 채움).
 */
interface ListOrdersUseCase {
    /**
     * 열린 주문(체결 대기·부분 체결·처리 중).
     * 주문 시각 내림차순.
     */
    fun openOrders(userId: UserId): List<OrderListing>

    /**
     * 오늘(한국 시간 0시부터) 닫힌 주문(체결·취소·거부·정정됨).
     * 분류·정렬은 증권사의 체결·취소 시각이고, 없는 주문만 수집 시각임.
     */
    fun todayClosedOrders(userId: UserId): List<OrderListing>
}
