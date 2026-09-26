package banghak.stock.core.domain.market

import banghak.stock.core.domain.error.InvalidValue
import banghak.stock.core.domain.money.Currency
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SymbolTest {
    @Test
    @DisplayName("국내 종목은 숫자 6자리")
    fun koreanCodeIsSixDigits() {
        assertThat(Symbol(Market.KR, "005930").code).isEqualTo("005930")
        assertThatThrownBy { Symbol(Market.KR, "5930") }.isInstanceOf(InvalidValue::class.java)
        assertThatThrownBy { Symbol(Market.KR, "00593A") }.isInstanceOf(InvalidValue::class.java)
    }

    @Test
    @DisplayName("미국 종목은 대문자 티커 1~5자")
    fun usCodeIsUppercaseTickerUpToFive() {
        assertThat(Symbol(Market.US, "NVDA").code).isEqualTo("NVDA")
        assertThat(Symbol(Market.US, "F").code).isEqualTo("F")
        assertThatThrownBy { Symbol(Market.US, "nvda") }.isInstanceOf(InvalidValue::class.java)
        assertThatThrownBy { Symbol(Market.US, "TOOLONG") }.isInstanceOf(InvalidValue::class.java)
        assertThatThrownBy { Symbol(Market.US, "") }.isInstanceOf(InvalidValue::class.java)
    }

    @Test
    @DisplayName("시장은 통화·시간대·수량 자릿수를 가짐")
    fun marketCarriesCurrencyZoneAndQuantityScale() {
        assertThat(Market.KR.currency).isEqualTo(Currency.KRW)
        assertThat(Market.KR.zone.id).isEqualTo("Asia/Seoul")
        assertThat(Market.KR.quantityScale).isZero()
        assertThat(Market.US.currency).isEqualTo(Currency.USD)
        assertThat(Market.US.zone.id).isEqualTo("America/New_York")
        assertThat(Market.US.quantityScale).isEqualTo(6)
    }

    @Test
    @DisplayName("기본 종목 상수는 삼성전자와 엔비디아임")
    fun defaultSymbolsAreSamsungAndNvidia() {
        assertThat(Symbol.DEFAULT_KR).isEqualTo(Symbol(Market.KR, "005930"))
        assertThat(Symbol.DEFAULT_US).isEqualTo(Symbol(Market.US, "NVDA"))
    }
}
