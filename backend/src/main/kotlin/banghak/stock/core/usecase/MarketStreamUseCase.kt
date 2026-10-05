package banghak.stock.core.usecase

import banghak.stock.core.domain.trading.StreamMessage
import banghak.stock.core.domain.trading.StreamViewerId
import banghak.stock.core.domain.trading.StreamWatch

/**
 * 로컬 실시간 스트림(현재가·호가·진행 봉·연결 상태).
 * 화면 연결마다 보는 종목을 선언하고, 데몬은 그 합집합을 증권사에 구독함.
 */
interface MarketStreamUseCase {
    /** [watch] 의 연결이 보는 종목 전체를 바꿈(빠진 종목은 해제). */
    fun watch(watch: StreamWatch)

    /** 연결이 끊겼을 때 그 연결의 구독을 거둠. */
    fun leave(viewer: StreamViewerId)

    /**
     * 모아 둔 최신 시세를 보는 연결마다 한 번에 밈.
     * 250ms 마다 호출함(초당 4회).
     */
    fun flush()

    /** 종목과 무관한 메시지(지수 티커 등)를 모든 연결에 바로 밈. */
    fun broadcast(message: StreamMessage)
}
