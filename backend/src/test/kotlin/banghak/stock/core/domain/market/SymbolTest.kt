package banghak.stock.core.domain.market

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.money.Currency
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SymbolTest {
    @Test
    @DisplayName("국내 종목은 영문 대문자·숫자 6자리")
    fun koreanCodeIsSixAlphanumerics() {
        assertThat(Symbol(Market.KR, "005930").code).isEqualTo("005930")
        assertThat(Symbol(Market.KR, "0010S0").code).isEqualTo("0010S0")
        assertThatThrownBy { Symbol(Market.KR, "5930") }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { Symbol(Market.KR, "00593a") }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("미국 종목은 대문자로 시작하는 티커이고 점·하이픈을 허용함")
    fun usCodeIsUppercaseTickerWithDotOrHyphen() {
        assertThat(Symbol(Market.US, "NVDA").code).isEqualTo("NVDA")
        assertThat(Symbol(Market.US, "F").code).isEqualTo("F")
        assertThat(Symbol(Market.US, "BRK.B").code).isEqualTo("BRK.B")
        assertThatThrownBy { Symbol(Market.US, "nvda") }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { Symbol(Market.US, ".ABC") }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { Symbol(Market.US, "") }.isInstanceOf(InvalidValueException::class.java)
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
