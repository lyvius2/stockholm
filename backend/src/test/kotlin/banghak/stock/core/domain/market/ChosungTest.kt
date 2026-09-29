package banghak.stock.core.domain.market

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ChosungTest {
    @Test
    @DisplayName("한글 음절은 초성으로 바꾸고 된소리 초성도 그대로 살림")
    fun convertsSyllablesToInitials() {
        assertThat(Chosung.of("삼성전자")).isEqualTo("ㅅㅅㅈㅈ")
        assertThat(Chosung.of("까뮤이앤씨")).isEqualTo("ㄲㅁㅇㅇㅆ")
    }

    @Test
    @DisplayName("한글 음절의 처음(가)과 끝(힣)도 초성으로 바꿈")
    fun coversSyllableRangeEnds() {
        assertThat(Chosung.of("가힣")).isEqualTo("ㄱㅎ")
    }

    @Test
    @DisplayName("영문·숫자·공백·자모는 그대로 둠")
    fun keepsOtherCharacters() {
        assertThat(Chosung.of("KODEX 200선물인버스2X")).isEqualTo("KODEX 200ㅅㅁㅇㅂㅅ2X")
        assertThat(Chosung.of("ㄱㄴ")).isEqualTo("ㄱㄴ")
    }
}
