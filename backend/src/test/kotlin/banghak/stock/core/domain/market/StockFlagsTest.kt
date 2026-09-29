package banghak.stock.core.domain.market

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class StockFlagsTest {
    private val samsung = Symbol(Market.KR, "005930")
    private val nvidia = Symbol(Market.US, "NVDA")
    private val now = Instant.parse("2026-09-29T01:00:00Z")
    private val trading =
        KrTradingDetail(false, true, isKrxSuspended = false, isNxtSuspended = false)

    @Test
    @DisplayName("매수 유의사항 종류를 플래그로 옮기고, 정적·동적 혼합 VI 는 두 VI 모두로 봄")
    fun mapsWarningTypes() {
        val flags =
            StockFlags.of(
                samsung,
                listOf(
                    warning(StockWarningType.INVESTMENT_WARNING),
                    warning(StockWarningType.OVERHEATED),
                    warning(StockWarningType.VI_STATIC_AND_DYNAMIC),
                ),
                trading,
                now,
            )

        assertThat(flags.isInvestmentWarning).isTrue()
        assertThat(flags.isOverheated).isTrue()
        assertThat(flags.isViStatic).isTrue()
        assertThat(flags.isViDynamic).isTrue()
        assertThat(flags.isInvestmentRisk).isFalse()
        assertThat(flags.isLiquidationTrading).isFalse()
    }

    @Test
    @DisplayName("KRX·NXT 어느 한쪽이라도 거래정지면 정지로 봄")
    fun haltedWhenEitherVenueSuspended() {
        val krxOnly = trading.copy(isKrxSuspended = true)
        val nxtOnly = trading.copy(isNxtSuspended = true)

        assertThat(halted(krxOnly)).isTrue()
        assertThat(halted(nxtOnly)).isTrue()
        assertThat(halted(trading)).isFalse()
    }

    @Test
    @DisplayName("NXT 지원 종목인데 NXT 정지 값이 없으면 모름이고, NXT 미지원 종목의 빈 값은 정지 아님으로 봄")
    fun missingNxtValueIsUnknownOnlyWhenSupported() {
        val supportedMissing = trading.copy(isNxtSuspended = null)
        val unsupported = trading.copy(isNxtSupported = false, isNxtSuspended = null)
        val krxHaltedAnyway = supportedMissing.copy(isKrxSuspended = true)

        assertThat(halted(supportedMissing)).isNull()
        assertThat(halted(unsupported)).isFalse()
        assertThat(halted(krxHaltedAnyway)).isTrue()
    }

    @Test
    @DisplayName("뜻을 모르는 유의사항이 걸리면 따로 표시하고 모르는 상태로 봄")
    fun unknownWarningIsKept() {
        val flags = StockFlags.of(samsung, listOf(warning(StockWarningType.UNKNOWN)), trading, now)

        assertThat(flags.hasUnknownWarning).isTrue()
        assertThat(flags.hasUnknownState).isTrue()
        assertThat(flags.isInvestmentWarning).isFalse()
    }

    @Test
    @DisplayName("정리매매는 유의사항이나 국내 거래 상태 어느 쪽에서 와도 표시함")
    fun liquidationFromEitherSource() {
        val fromDetail = trading.copy(isLiquidationTrading = true)

        assertThat(StockFlags.of(samsung, emptyList(), fromDetail, now).isLiquidationTrading)
            .isTrue()
        assertThat(
                StockFlags.of(
                        samsung,
                        listOf(warning(StockWarningType.LIQUIDATION_TRADING)),
                        trading,
                        now,
                    )
                    .isLiquidationTrading
            )
            .isTrue()
    }

    @Test
    @DisplayName("출처가 없는 값은 모름(null)으로 둠: 관리종목은 항상, 거래 상태가 없는 종목의 거래정지도")
    fun unknownWithoutSource() {
        val us = StockFlags.of(nvidia, emptyList(), krDetail = null, asOf = now)

        assertThat(us.isTradingHalted).isNull()
        assertThat(us.isAdministrative).isNull()
        assertThat(us.hasUnknownState).isTrue()
        assertThat(StockFlags.of(samsung, emptyList(), trading, now).isAdministrative).isNull()
    }

    private fun halted(detail: KrTradingDetail): Boolean? =
        StockFlags.of(samsung, emptyList(), detail, now).isTradingHalted

    private fun warning(type: StockWarningType) = StockWarning(type, startsOn = null, endsOn = null)
}
