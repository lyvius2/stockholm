package banghak.stock.core.domain.account

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.trading.TradingFixtures
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class UserSettingCodecTest {
    @Test
    @DisplayName("직전 종목과 기본 시장은 JSON 으로 적고 같은 값으로 읽음")
    fun roundTrips() {
        val viewed = LastViewedStock(TradingFixtures.nvidia, Instant.parse("2026-10-02T01:02:03Z"))

        val json = UserSettingCodec.lastViewedStock(viewed)

        assertThat(json)
            .isEqualTo("""{"market":"US","code":"NVDA","viewedAt":"2026-10-02T01:02:03Z"}""")
        assertThat(UserSettingCodec.parseLastViewedStock(json)).isEqualTo(viewed)
        assertThat(UserSettingCodec.parseDefaultMarket(UserSettingCodec.defaultMarket(Market.US)))
            .isEqualTo(Market.US)
    }

    @Test
    @DisplayName("필드가 빠졌거나 빈 값이면 거부함")
    fun rejectsMalformed() {
        assertThatThrownBy { UserSettingCodec.parseLastViewedStock("""{"market":"KR"}""") }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy {
                UserSetting(TradingFixtures.user, UserSettingKey.DEFAULT_MARKET, " ", Instant.EPOCH)
            }
            .isInstanceOf(InvalidValueException::class.java)
    }
}
