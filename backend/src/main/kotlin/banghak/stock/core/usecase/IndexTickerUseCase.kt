package banghak.stock.core.usecase

import banghak.stock.core.domain.market.IndexTicker

/**
 * 상단 바 지수 티커(F23).
 * 정보 표시일 뿐 자동 주문의 입력이 아님.
 */
interface LookupIndexTickerUseCase {
    /**
     * 마지막으로 만든 티커.
     * 첫 갱신 전에는 저장된 마지막 값으로 채운 세트임(지연 표시).
     */
    fun current(): IndexTicker
}

/** 5분마다 세트를 고르고 값을 새로 받아 화면에 밈. */
interface RefreshIndexTickerUseCase {
    fun refresh(): IndexTicker
}
