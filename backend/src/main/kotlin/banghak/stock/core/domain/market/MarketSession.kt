package banghak.stock.core.domain.market

import banghak.stock.core.domain.error.InvalidValueException
import java.time.Instant
import java.time.LocalDate

/**
 * 장 세션.
 * `DAY_MARKET` 은 토스가 미국 주식에 따로 여는 데이마켓(KST 낮)임.
 */
enum class MarketSession {
    DAY_MARKET,
    PRE,
    REGULAR,
    AFTER,
    CLOSED,
}

/**
 * 세션 하나의 시간 창.
 * 국내 동시호가 구간은 [auctionStart]~[end](정규장 15:20~15:30) 또는 [start]~[auctionEnd](애프터 15:30~15:40)로 표현함.
 */
data class SessionWindow(
    val session: MarketSession,
    val start: Instant,
    val end: Instant,
    val auctionStart: Instant? = null,
    val auctionEnd: Instant? = null,
) {
    init {
        if (session == MarketSession.CLOSED) throw InvalidValueException("CLOSED 는 시간 창을 갖지 않음")
        if (!end.isAfter(start)) throw InvalidValueException("세션 종료가 시작보다 앞섬: $session $start~$end")
    }

    /** 시작은 포함, 종료는 제외함. */
    fun contains(at: Instant): Boolean = !at.isBefore(start) && at.isBefore(end)
}

/**
 * 한 거래일의 세션 목록.
 * 휴장일은 세션이 비어 있음.
 */
data class TradingDay(val market: Market, val date: LocalDate, val sessions: List<SessionWindow>) {
    init {
        val kinds = sessions.map { it.session }
        if (kinds.size != kinds.toSet().size) throw InvalidValueException("같은 세션이 두 번 있음: $kinds")
    }

    val isHoliday: Boolean
        get() = sessions.isEmpty()

    fun sessionAt(at: Instant): MarketSession =
        sessions.firstOrNull { it.contains(at) }?.session ?: MarketSession.CLOSED

    fun window(session: MarketSession): SessionWindow? = sessions.firstOrNull {
        it.session == session
    }
}
