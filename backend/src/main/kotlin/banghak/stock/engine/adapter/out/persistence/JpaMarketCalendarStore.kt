package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.MarketSession
import banghak.stock.core.domain.market.SessionWindow
import banghak.stock.core.domain.market.TradingDay
import banghak.stock.core.port.MarketCalendarStorePort
import banghak.stock.engine.adapter.out.persistence.entity.MarketCalendarEntity
import banghak.stock.engine.adapter.out.persistence.entity.MarketDateKey
import banghak.stock.engine.adapter.out.persistence.repository.MarketCalendarRepository
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Instant
import java.time.LocalDate
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper

@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaMarketCalendarStore(
    private val repository: MarketCalendarRepository,
    private val mapper: JsonMapper,
) : MarketCalendarStorePort {
    /**
     * 세션 하나의 저장 모양.
     * 시각은 ISO-8601 UTC.
     */
    data class SessionRow(
        val session: String = "",
        val start: Instant = Instant.EPOCH,
        val end: Instant = Instant.EPOCH,
        val auctionStart: Instant? = null,
        val auctionEnd: Instant? = null,
    )

    @Transactional(readOnly = true)
    override fun find(market: Market, date: LocalDate): MarketCalendarStorePort.Stored? =
        repository.findById(MarketDateKey(market.name, date.toString())).orElse(null)?.let {
            MarketCalendarStorePort.Stored(dayOf(market, date, it), it.fetchedAt)
        }

    @Transactional
    override fun save(day: TradingDay, fetchedAt: Instant) {
        val key = MarketDateKey(day.market.name, day.date.toString())
        val json = mapper.writeValueAsString(day.sessions.map(::rowOf))
        val row = repository.findById(key).orElse(null)
        if (row == null) {
            repository.save(MarketCalendarEntity(key, json, day.isHoliday, fetchedAt))
            return
        }
        row.sessionsJson = json
        row.isHoliday = day.isHoliday
        row.fetchedAt = fetchedAt
    }

    private fun dayOf(market: Market, date: LocalDate, row: MarketCalendarEntity): TradingDay {
        val rows: List<SessionRow> =
            mapper.readValue(
                row.sessionsJson,
                mapper.typeFactory.constructCollectionType(
                    List::class.java,
                    SessionRow::class.java,
                ),
            )
        return TradingDay(
            market,
            date,
            rows.map {
                SessionWindow(
                    MarketSession.valueOf(it.session),
                    it.start,
                    it.end,
                    it.auctionStart,
                    it.auctionEnd,
                )
            },
        )
    }

    private fun rowOf(window: SessionWindow) =
        SessionRow(
            window.session.name,
            window.start,
            window.end,
            window.auctionStart,
            window.auctionEnd,
        )
}
