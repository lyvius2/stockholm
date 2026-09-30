package banghak.stock.core.domain.trading

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.money.Percent
import banghak.stock.core.domain.trading.TradingFixtures.krw
import banghak.stock.core.domain.trading.TradingFixtures.usd
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class OrderTicketTest {
    private val samsung = TradingFixtures.samsung

    @Test
    @DisplayName("정확히 상한가·하한가는 허용하고 1원이라도 벗어나면 허용하지 않음")
    fun limitsIncludeBoundaries() {
        val limits = PriceLimits(samsung, krw("91000"), krw("49000"), TradingFixtures.now)

        assertThat(limits.allows(krw("91000"))).isTrue()
        assertThat(limits.allows(krw("49000"))).isTrue()
        assertThat(limits.allows(krw("91001"))).isFalse()
        assertThat(limits.allows(krw("48999"))).isFalse()
    }

    @Test
    @DisplayName("가격 제한이 없는 시장은 어떤 가격도 허용함")
    fun noLimitsAllowAnyPrice() {
        val limits = PriceLimits(TradingFixtures.nvidia, null, null, TradingFixtures.now)

        assertThat(limits.allows(usd("99999"))).isTrue()
    }

    @Test
    @DisplayName("상·하한가 통화는 시장 통화와 같아야 함")
    fun limitsInMarketCurrency() {
        assertThatThrownBy { PriceLimits(samsung, usd("100"), null, TradingFixtures.now) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("수수료율 적용 기간은 시작일과 종료일을 포함하고, 비어 있으면 기한이 없음")
    fun commissionPeriodIsInclusive() {
        val start = LocalDate.of(2026, 9, 1)
        val end = LocalDate.of(2026, 9, 30)
        val rate = CommissionRate(Market.KR, Percent.ofRatio("0.00015"), start, end)

        assertThat(rate.isEffectiveOn(start)).isTrue()
        assertThat(rate.isEffectiveOn(end)).isTrue()
        assertThat(rate.isEffectiveOn(start.minusDays(1))).isFalse()
        assertThat(rate.isEffectiveOn(end.plusDays(1))).isFalse()
        assertThat(rate.copy(startDate = null, endDate = null).isEffectiveOn(end.plusYears(9)))
            .isTrue()
    }
}
