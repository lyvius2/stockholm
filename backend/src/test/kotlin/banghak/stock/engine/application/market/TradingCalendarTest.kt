package banghak.stock.engine.application.market

import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.MarketSession
import banghak.stock.core.domain.market.SessionWindow
import banghak.stock.core.domain.market.TradingDay
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.MemoryMarketCalendarStore
import banghak.stock.support.fakes.ScriptedMarketCalendar
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class TradingCalendarTest {
    // 한국 시간 2026-10-01 10:00
    private val clock = MutableClock(Instant.parse("2026-10-01T01:00:00Z"))
    private val broker = ScriptedMarketCalendar()
    private val store = MemoryMarketCalendarStore()
    private val calendar = TradingCalendar(broker, store, clock)
    private val yesterday = LocalDate.of(2026, 9, 30)
    private val today = LocalDate.of(2026, 10, 1)

    @Test
    @DisplayName("지난 날은 한 번 받아 저장하면 다시 묻지 않고, 오늘은 한 시간이 지나면 다시 받음")
    fun cachesPastDaysForeverAndTodayForAnHour() {
        calendar.tradingDay(Market.KR, yesterday)
        calendar.tradingDay(Market.KR, yesterday)
        calendar.tradingDay(Market.KR, today)
        clock.advance(Duration.ofMinutes(59))
        calendar.tradingDay(Market.KR, today)
        clock.advance(Duration.ofMinutes(2))
        calendar.tradingDay(Market.KR, today)

        assertThat(broker.requests)
            .containsExactly(Market.KR to yesterday, Market.KR to today, Market.KR to today)
        assertThat(store.count()).isEqualTo(2)
    }

    @Test
    @DisplayName("저장된 날은 새 인스턴스(데몬 재시작)에서도 토스를 부르지 않음")
    fun survivesRestart() {
        calendar.tradingDay(Market.US, yesterday)

        TradingCalendar(broker, store, clock).tradingDay(Market.US, yesterday)

        assertThat(broker.requests).hasSize(1)
    }

    @Test
    @DisplayName("토스를 받지 못하면 저장된 달력을 쓰되 받은 시각은 그대로 두어, 토스가 1분 뒤 돌아오면 바로 다시 받음")
    fun fallsBackToStoredWithoutExtendingItsAge() {
        calendar.tradingDay(Market.KR, today)
        clock.advance(Duration.ofHours(2))
        broker.failure = MarketDataUnavailableException("토스에 연결할 수 없음")

        assertThat(calendar.tradingDay(Market.KR, today).date).isEqualTo(today)
        assertThatThrownBy { calendar.tradingDay(Market.KR, yesterday) }
            .isInstanceOf(MarketDataUnavailableException::class.java)
        val requestsAfterFailure = broker.requests.size

        // 실패 직후 1분 안에는 다시 묻지 않음(차트가 봉마다 묻기 때문)
        calendar.tradingDay(Market.KR, today)
        assertThatThrownBy { calendar.tradingDay(Market.KR, yesterday) }
            .isInstanceOf(MarketDataUnavailableException::class.java)
        assertThat(broker.requests).hasSize(requestsAfterFailure)

        // 토스가 돌아오면 한 시간을 기다리지 않고 곧 다시 받음
        broker.failure = null
        clock.advance(Duration.ofMinutes(1))
        calendar.tradingDay(Market.KR, today)
        assertThat(broker.requests).hasSize(requestsAfterFailure + 1)
    }

    @Test
    @DisplayName("앞뒤 날 하나를 받지 못해도 받은 날의 세션은 찾음")
    fun sessionLookupIsolatesFailedDays() {
        val regularOpen = Instant.parse("2026-09-30T00:00:00Z")
        broker.days[Market.KR to yesterday] =
            TradingDay(
                Market.KR,
                yesterday,
                listOf(
                    SessionWindow(
                        MarketSession.REGULAR,
                        regularOpen,
                        regularOpen.plus(Duration.ofHours(6)),
                    )
                ),
            )
        calendar.tradingDay(Market.KR, yesterday)
        broker.failure = MarketDataUnavailableException("토스에 연결할 수 없음")

        assertThat(calendar.sessionStartAt(Market.KR, regularOpen.plus(Duration.ofHours(1))))
            .isEqualTo(regularOpen)
    }

    @Test
    @DisplayName("세션 시작은 날짜를 넘는 세션도 찾음: 미국 애프터마켓(한국 시간 다음 날 새벽)은 전날 달력에 있음")
    fun findsSessionAcrossDateBoundary() {
        // 뉴욕 9/30 애프터 16:00~20:00 = UTC 20:00~00:00(10/1)
        val afterStart = OffsetDateTime.parse("2026-09-30T16:00:00-04:00").toInstant()
        broker.days[Market.US to yesterday] =
            TradingDay(
                Market.US,
                yesterday,
                listOf(
                    SessionWindow(
                        MarketSession.AFTER,
                        afterStart,
                        OffsetDateTime.parse("2026-09-30T20:00:00-04:00").toInstant(),
                    )
                ),
            )

        val inAfter = OffsetDateTime.parse("2026-09-30T19:59:00-04:00").toInstant()
        assertThat(calendar.sessionStartAt(Market.US, inAfter)).isEqualTo(afterStart)
        assertThat(calendar.sessionStartAt(Market.US, afterStart.plus(Duration.ofHours(5))))
            .isNull()
    }

    @Test
    @DisplayName("미리 받기는 시장마다 90일 전부터 내일까지 없는 날을 받고, 실패하면 거기서 멈춰 다음에 이어 함")
    fun backfillsRecentDays() {
        calendar.syncRecent()

        assertThat(broker.requests).hasSize(92 * 2)
        assertThat(broker.requests.first()).isEqualTo(Market.KR to today.minusDays(90))
        // 미국의 오늘은 뉴욕 날짜(아직 9/30)라 내일은 10/1
        assertThat(broker.requests.last()).isEqualTo(Market.US to today)

        broker.requests.clear()
        calendar.syncRecent()
        // 지난 날은 저장돼 있고 오늘·내일만 아직 한 시간이 안 지나 다시 받지 않음
        assertThat(broker.requests).isEmpty()
    }

    @Test
    @DisplayName("미리 받기는 한 날이 실패해도 나머지 날과 다른 시장을 계속하되, 한 시장에서 세 번 연달아 실패하면 그 시장만 멈춤")
    fun backfillContinuesPastSingleFailures() {
        var remaining = 1
        val flaky =
            object : banghak.stock.core.port.MarketCalendarPort {
                val requests = mutableListOf<Pair<Market, LocalDate>>()

                override fun tradingDay(market: Market, date: LocalDate): TradingDay {
                    requests += market to date
                    if (market == Market.KR && remaining-- > 0)
                        throw MarketDataUnavailableException("한 번 실패")
                    return TradingDay(market, date, emptyList())
                }
            }
        TradingCalendar(flaky, MemoryMarketCalendarStore(), clock).syncRecent()
        assertThat(flaky.requests).hasSize(92 * 2)

        val down =
            object : banghak.stock.core.port.MarketCalendarPort {
                val requests = mutableListOf<Pair<Market, LocalDate>>()

                override fun tradingDay(market: Market, date: LocalDate): TradingDay {
                    requests += market to date
                    if (market == Market.KR) throw MarketDataUnavailableException("토스 장애")
                    return TradingDay(market, date, emptyList())
                }
            }
        TradingCalendar(down, MemoryMarketCalendarStore(), clock).syncRecent()
        assertThat(down.requests.count { it.first == Market.KR }).isEqualTo(3)
        assertThat(down.requests.count { it.first == Market.US }).isEqualTo(92)
    }
}
