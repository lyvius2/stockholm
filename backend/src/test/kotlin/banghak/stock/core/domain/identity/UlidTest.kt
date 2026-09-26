package banghak.stock.core.domain.identity

import banghak.stock.core.domain.error.InvalidValue
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class UlidTest {
    private val time = Instant.parse("2016-07-30T23:16:16.385Z")
    private val entropy = ByteArray(10) { it.toByte() }

    @Test
    @DisplayName("26자 Crockford base32이고 앞 10자는 시각임")
    fun encodesTimeInFirstTenOfTwentySixChars() {
        val ulid = Ulid.of(time, entropy)
        assertThat(ulid.value).hasSize(26).matches("[0-9A-HJKMNP-TV-Z]{26}")
        assertThat(ulid.value).startsWith("01ARZ1G0W1")
        assertThat(ulid.timestamp()).isEqualTo(time)
    }

    @Test
    @DisplayName("같은 입력은 같은 값, 시각이 늦으면 사전순으로 뒤임")
    fun isDeterministicAndLaterTimeSortsAfter() {
        assertThat(Ulid.of(time, entropy)).isEqualTo(Ulid.of(time, entropy))
        val later = Ulid.of(time.plusMillis(1), entropy)
        assertThat(later.value).isGreaterThan(Ulid.of(time, entropy).value)
    }

    @Test
    @DisplayName("엔트로피는 10바이트여야 함")
    fun rejectsEntropyOtherThanTenBytes() {
        assertThatThrownBy { Ulid.of(time, ByteArray(9)) }.isInstanceOf(InvalidValue::class.java)
    }

    @Test
    @DisplayName("문자열 파싱은 형식을 검증함")
    fun parseValidatesFormat() {
        assertThat(Ulid.parse("01ARYZ6S41TSV4RRFFQ69G5FAV").value)
            .isEqualTo("01ARYZ6S41TSV4RRFFQ69G5FAV")
        assertThatThrownBy { Ulid.parse("01ARYZ6S41TSV4RRFFQ69G5FA") }
            .isInstanceOf(InvalidValue::class.java)
        assertThatThrownBy { Ulid.parse("01ARYZ6S41TSV4RRFFQ69G5FAI") }
            .isInstanceOf(InvalidValue::class.java)
    }
}
