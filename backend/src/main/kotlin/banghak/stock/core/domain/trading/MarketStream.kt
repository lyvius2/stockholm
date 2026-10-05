package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.IndexTicker
import banghak.stock.core.domain.market.Symbol

/** 로컬 실시간 스트림을 보는 화면 연결 하나. */
@JvmInline
value class StreamViewerId(val value: String) {
    init {
        if (value.isBlank()) throw InvalidValueException("스트림 연결 식별자가 비어 있음")
    }
}

/**
 * 데몬이 화면에 미는 실시간 메시지.
 * 시세 세 가지는 종목별 최신값만 묶어 보내고, 연결 상태는 바로 보냄.
 */
sealed interface StreamMessage {
    data class QuoteUpdate(val quote: Quote) : StreamMessage

    data class OrderBookUpdate(val orderBook: OrderBook) : StreamMessage

    data class LiveCandleUpdate(val candle: Candle) : StreamMessage

    /** 상단 바 지수 티커(5분마다, 모든 화면에). */
    data class IndexTickerUpdate(val ticker: IndexTicker) : StreamMessage

    /**
     * [isLive] 가 false 면 증권사 시세 연결이 끊긴 것이라 화면은 지연을 표시함.
     * [unavailableSymbols] 는 연결은 됐지만 이 화면이 보는 종목 중 증권사가 거절했거나 구독 한도 밖이라 시세가 오지 않는 종목임.
     */
    data class MarketFeedState(
        val isLive: Boolean,
        val unavailableSymbols: Set<Symbol> = emptySet(),
    ) : StreamMessage
}

/**
 * 화면 연결 하나가 보는 종목.
 * 화면은 네 영역의 종목·관심종목 등 보이는 것을 통째로 선언함.
 */
data class StreamWatch(val viewer: StreamViewerId, val symbols: Set<Symbol>) {
    init {
        if (symbols.size > MAX_SYMBOLS)
            throw InvalidValueException("한 화면이 보는 종목은 ${MAX_SYMBOLS}개까지: ${symbols.size}")
    }

    companion object {
        const val MAX_SYMBOLS = 20
    }
}
