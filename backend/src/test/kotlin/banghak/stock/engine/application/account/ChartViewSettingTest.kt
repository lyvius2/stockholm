package banghak.stock.engine.application.account

import banghak.stock.core.domain.account.ChartView
import banghak.stock.core.domain.account.SimpleChartRange
import banghak.stock.core.domain.account.UserSettingCodec
import banghak.stock.core.domain.eventlog.UserSettingChanged
import banghak.stock.core.domain.trading.ChartResolution
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.MemoryEventStore
import banghak.stock.support.fakes.MemoryUserSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ChartViewSettingTest {
    private val clock = MutableClock(TradingFixtures.now)
    private val events = MemoryEventStore()
    private val service = UserSettingsService(MemoryUserSettings(), events, clock)
    private val user = TradingFixtures.user

    @Test
    @DisplayName("저장 전에는 기본값이고, 저장하면 같은 값을 읽으며 동기화 이벤트가 남음")
    fun defaultsThenRoundTrips() {
        assertThat(service.chartView(user)).isEqualTo(ChartView.DEFAULT)

        val view = ChartView(true, SimpleChartRange.WEEK_1, ChartResolution.MINUTE_30)
        service.saveChartView(user, TradingFixtures.device, view)

        assertThat(service.chartView(user)).isEqualTo(view)
        assertThat(events.replay(user, null, 0).map { it.payload }.toList())
            .containsExactly(UserSettingChanged("CHART_VIEW", UserSettingCodec.chartView(view)))
    }
}
