package banghak.stock.core.domain.market

import banghak.stock.core.domain.error.InvalidValueException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class RankingQueryTest {
    @Test
    @DisplayName("급등·급락 랭킹은 실시간 기간으로 만들 수 없고, 거래대금 랭킹은 만들 수 있음")
    fun changeRateRankingHasNoRealtime() {
        assertThatThrownBy { query(RankingType.TOP_GAINERS, RankingPeriod.REALTIME) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { query(RankingType.TOP_LOSERS, RankingPeriod.REALTIME) }
            .isInstanceOf(InvalidValueException::class.java)

        assertThat(query(RankingType.MARKET_TRADING_AMOUNT, RankingPeriod.REALTIME).count)
            .isEqualTo(100)
        assertThat(query(RankingType.TOP_GAINERS, RankingPeriod.DAY_1)).isNotNull
    }

    @Test
    @DisplayName("조회 수는 1~100 만 받음")
    fun countWithinRange() {
        assertThatThrownBy { query(count = 0) }.isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { query(count = 101) }.isInstanceOf(InvalidValueException::class.java)
        assertThat(query(count = 1).count).isEqualTo(1)
    }

    private fun query(
        type: RankingType = RankingType.MARKET_TRADING_VOLUME,
        period: RankingPeriod = RankingPeriod.DAY_1,
        count: Int = 100,
    ) = RankingQuery(Market.KR, type, period, excludesInvestmentCaution = false, count = count)
}
