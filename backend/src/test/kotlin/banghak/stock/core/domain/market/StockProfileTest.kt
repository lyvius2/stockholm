package banghak.stock.core.domain.market

import banghak.stock.core.domain.error.InvalidValueException
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class StockProfileTest {
    private val samsungPreferred = Symbol(Market.KR, "005935")

    @Test
    @DisplayName("보통주가 아닌 주식은 우선주로 보고, ETF 는 보통주가 아니어도 우선주가 아님")
    fun preferredOnlyForNonCommonStock() {
        assertThat(profile(SecurityType.STOCK, isCommonShare = false).isPreferred).isTrue()
        assertThat(profile(SecurityType.STOCK, isCommonShare = true).isPreferred).isFalse()
        assertThat(profile(SecurityType.ETF, isCommonShare = false).isPreferred).isFalse()
    }

    @Test
    @DisplayName("종목의 시장과 상장 시장이 다르면 거부함")
    fun rejectsBoardOfOtherMarket() {
        assertThatThrownBy { profile(board = ListingBoard.NASDAQ) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("국내 거래 상태는 미국 종목에 둘 수 없음")
    fun rejectsKrDetailOnUsStock() {
        assertThatThrownBy {
                profile().copy(symbol = Symbol(Market.US, "NVDA"), board = ListingBoard.NASDAQ)
            }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("초성 검색 키는 종목명에서 만듦")
    fun chosungFromName() {
        assertThat(profile().chosung).isEqualTo("ㅅㅅㅈㅈㅇ")
    }

    private fun profile(
        securityType: SecurityType = SecurityType.STOCK,
        isCommonShare: Boolean = false,
        board: ListingBoard = ListingBoard.KOSPI,
    ) =
        StockProfile(
            symbol = samsungPreferred,
            name = "삼성전자우",
            englishName = "SamsungElec(1P)",
            isin = "KR7005931001",
            board = board,
            securityType = securityType,
            isCommonShare = isCommonShare,
            status = ListingStatus.ACTIVE,
            listedOn = null,
            delistedOn = null,
            sharesOutstanding = BigDecimal("815974664"),
            leverageFactor = null,
            krDetail = KrTradingDetail(false, true, isKrxSuspended = false, isNxtSuspended = false),
        )
}
