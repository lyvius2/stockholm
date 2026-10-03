package banghak.stock.engine.application.market

import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.MarketSession
import banghak.stock.core.domain.market.SessionWindow
import banghak.stock.core.domain.market.TradingDay
import banghak.stock.core.port.MarketCalendarPort
import banghak.stock.core.port.MarketCalendarStorePort
import banghak.stock.core.usecase.SyncMarketCalendarUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 장 달력을 저장소에서 먼저 찾고 없으면 토스에서 받아 저장함(cache-aside).
 * 지난 날은 바뀌지 않아 한 번만 받고, 오늘·앞날은 받은 지 한 시간이 지나면 다시 받음(임시 휴장 반영).
 * 토스를 받지 못하면 저장된 것을 그대로 씀.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class TradingCalendar(
    private val broker: MarketCalendarPort,
    private val store: MarketCalendarStorePort,
    private val clock: Clock,
) : SyncMarketCalendarUseCase {
    /**
     * 메모리 항목.
     * 성공해 받은 값([stored])과 마지막 실패 시각([failedAt])을 따로 둠.
     */
    private data class Entry(val stored: MarketCalendarStorePort.Stored?, val failedAt: Instant?)

    // 차트 한 번에 봉 수만큼 묻기 때문에 저장소도 매번 읽지 않고 메모리에 둠
    private val cache = ConcurrentHashMap<Pair<Market, LocalDate>, Entry>()

    /** @throws MarketDataUnavailableException 받지 못했고 저장된 것도 없으면 발생함 */
    fun tradingDay(market: Market, date: LocalDate): TradingDay {
        val key = market to date
        val entry = cache[key] ?: Entry(store.find(market, date), null).also { cache[key] = it }
        val stored = entry.stored
        if (stored != null && !isStale(market, date, stored.fetchedAt)) return stored.day
        // 받지 못한 직후에는 잠시 다시 묻지 않음(차트가 봉마다 묻기 때문)
        if (isRetryTooSoon(entry.failedAt))
            return stored?.day
                ?: throw MarketDataUnavailableException("$market $date 달력을 방금 받지 못해 잠시 뒤 다시 물음")
        return fetch(key, stored)
    }

    /**
     * [at] 이 들어 있는 세션의 시작 시각.
     * 세션이 날짜를 넘을 수 있어(미국 데이마켓·애프터는 한국 시간 기준으로 다른 날) 앞뒤 날도 봄.
     * 한 날의 달력을 받지 못해도 다른 날의 세션은 찾음.
     * 장 밖이거나 그 날 달력을 모르면 null.
     */
    fun sessionStartAt(market: Market, at: Instant): Instant? = windowAt(market, at)?.start

    /**
     * [at] 의 장 세션.
     * 장 밖이거나 달력을 모르면 CLOSED.
     */
    fun sessionAt(market: Market, at: Instant): MarketSession =
        windowAt(market, at)?.session ?: MarketSession.CLOSED

    private fun windowAt(market: Market, at: Instant): SessionWindow? {
        val date = at.atZone(market.zone).toLocalDate()
        return listOf(date.minusDays(1), date, date.plusDays(1))
            .asSequence()
            .flatMap { sessionsOrEmpty(market, it) }
            .firstOrNull { it.contains(at) }
    }

    // 날짜 하나가 실패해도 시장과 나머지 날짜는 계속 받되, 한 시장에서 연달아 실패하면 그 시장은 다음으로 미룸
    override fun syncRecent() {
        Market.entries.forEach { market ->
            val today = today(market)
            var failures = 0
            var date = today.minusDays(BACKFILL_DAYS)
            while (!date.isAfter(today.plusDays(1)) && failures < MAX_CONSECUTIVE_FAILURES) {
                failures =
                    try {
                        tradingDay(market, date)
                        0
                    } catch (e: MarketDataUnavailableException) {
                        log.warn("{} {} 달력을 받지 못함({}). 다음에 다시 함", market, date, e::class.simpleName)
                        failures + 1
                    }
                date = date.plusDays(1)
            }
        }
    }

    private fun sessionsOrEmpty(market: Market, date: LocalDate): List<SessionWindow> =
        try {
            tradingDay(market, date).sessions
        } catch (e: MarketDataUnavailableException) {
            emptyList()
        }

    // 받은 시각은 성공했을 때만 바뀜.
    // 실패해 저장된 것을 쓰면 다음 재시도 시각만 적어 토스가 회복되면 곧 다시 받음
    private fun fetch(
        key: Pair<Market, LocalDate>,
        fallback: MarketCalendarStorePort.Stored?,
    ): TradingDay {
        val (market, date) = key
        return try {
            val now = clock.instant()
            val day = broker.tradingDay(market, date)
            store.save(day, now)
            cache[key] = Entry(MarketCalendarStorePort.Stored(day, now), null)
            day
        } catch (e: MarketDataUnavailableException) {
            cache[key] = Entry(fallback, clock.instant())
            fallback?.day?.also { log.warn("{} {} 달력을 새로 받지 못해 저장된 것을 씀", market, date) } ?: throw e
        }
    }

    private fun isRetryTooSoon(failedAt: Instant?): Boolean =
        failedAt != null && Duration.between(failedAt, clock.instant()) < RETRY_INTERVAL

    private fun isStale(market: Market, date: LocalDate, fetchedAt: Instant): Boolean =
        !isPast(market, date) && Duration.between(fetchedAt, clock.instant()) > TODAY_TTL

    private fun isPast(market: Market, date: LocalDate): Boolean = date.isBefore(today(market))

    private fun today(market: Market): LocalDate = clock.instant().atZone(market.zone).toLocalDate()

    companion object {
        private val log = LoggerFactory.getLogger(TradingCalendar::class.java)

        // 임시 휴장·조기 종료가 당일에 바뀔 수 있어 오늘·앞날은 한 시간마다 다시 받음
        private val TODAY_TTL: Duration = Duration.ofHours(1)

        // 1분봉 보존 기간(90일)에 맞춰 그만큼의 달력을 미리 받아 둠
        private const val BACKFILL_DAYS = 90L

        // 받지 못한 날짜는 이 간격으로만 다시 물음
        private val RETRY_INTERVAL: Duration = Duration.ofMinutes(1)

        // 한 시장에서 이만큼 연달아 실패하면 토스 장애로 보고 그 시장의 미리 받기를 멈춤
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
