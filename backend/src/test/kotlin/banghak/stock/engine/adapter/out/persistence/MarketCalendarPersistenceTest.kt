package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.MarketSession
import banghak.stock.core.domain.market.SessionWindow
import banghak.stock.core.domain.market.TradingDay
import banghak.stock.core.port.MarketCalendarStorePort
import banghak.stock.support.EngineDatabaseTest
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/** 장 달력이 세션 시각(동시호가 포함)을 그대로 되살리고 휴장일도 남김. */
class MarketCalendarPersistenceTest : EngineDatabaseTest() {
    @Autowired private lateinit var store: MarketCalendarStorePort
    @Autowired private lateinit var engineWriteDataSource: HikariDataSource

    private val write by lazy { JdbcTemplate(engineWriteDataSource) }
    private val date = LocalDate.of(2026, 9, 30)
    private val fetchedAt = Instant.parse("2026-09-30T00:00:00Z")

    @BeforeEach
    fun clearTable() {
        write.update("delete from market_calendar")
    }

    @Test
    @DisplayName("세션 목록을 저장하고 같은 값으로 읽으며, 다시 저장하면 바뀜")
    fun roundTripsSessions() {
        val day =
            TradingDay(
                Market.KR,
                date,
                listOf(
                    SessionWindow(
                        MarketSession.REGULAR,
                        Instant.parse("2026-09-30T00:00:00Z"),
                        Instant.parse("2026-09-30T06:30:00Z"),
                        auctionStart = Instant.parse("2026-09-30T06:20:00Z"),
                    ),
                    SessionWindow(
                        MarketSession.AFTER,
                        Instant.parse("2026-09-30T06:30:00Z"),
                        Instant.parse("2026-09-30T11:00:00Z"),
                        auctionEnd = Instant.parse("2026-09-30T06:40:00Z"),
                    ),
                ),
            )

        store.save(day, fetchedAt)
        assertThat(store.find(Market.KR, date))
            .isEqualTo(MarketCalendarStorePort.Stored(day, fetchedAt))
        assertThat(store.find(Market.US, date)).isNull()

        store.save(day.copy(sessions = day.sessions.take(1)), fetchedAt.plusSeconds(60))
        assertThat(store.find(Market.KR, date)?.day?.sessions).hasSize(1)
        assertThat(write.queryForObject("select count(*) from market_calendar", Int::class.java))
            .isEqualTo(1)
    }

    @Test
    @DisplayName("휴장일은 세션 없이 저장되고 휴장 표시가 남음")
    fun storesHoliday() {
        store.save(TradingDay(Market.US, date, emptyList()), fetchedAt)

        assertThat(store.find(Market.US, date)?.day?.isHoliday).isTrue()
        assertThat(write.queryForObject("select is_holiday from market_calendar", Int::class.java))
            .isEqualTo(1)
    }
}
