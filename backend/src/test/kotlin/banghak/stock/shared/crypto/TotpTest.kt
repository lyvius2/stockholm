package banghak.stock.shared.crypto

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class TotpTest {
    // RFC 6238 부록 B 의 SHA-1 시드
    private val seed = "12345678901234567890".toByteArray()

    @Test
    @DisplayName("RFC 6238 부록 B 테스트 벡터(SHA-1, 30초, 6자리)")
    fun rfcVectors() {
        val vectors =
            mapOf(
                59L to "287082",
                1111111109L to "081804",
                1111111111L to "050471",
                1234567890L to "005924",
                2000000000L to "279037",
                20000000000L to "353130",
            )
        for ((seconds, expected) in vectors) {
            assertThat(Totp.generate(seed, Totp.counterAt(Instant.ofEpochSecond(seconds))))
                .describedAs("T=$seconds")
                .isEqualTo(expected)
        }
    }

    @Test
    @DisplayName("앞뒤 한 구간(±30초)까지 허용하고 그 밖은 거부함")
    fun windowOfOneStep() {
        val now = Instant.ofEpochSecond(1111111111L)
        val current = Totp.counterAt(now)
        assertThat(Totp.matchingCounter(seed, Totp.generate(seed, current), now)).isEqualTo(current)
        assertThat(Totp.matchingCounter(seed, Totp.generate(seed, current - 1), now))
            .isEqualTo(current - 1)
        assertThat(Totp.matchingCounter(seed, Totp.generate(seed, current + 1), now))
            .isEqualTo(current + 1)
        assertThat(Totp.matchingCounter(seed, Totp.generate(seed, current + 2), now)).isNull()
        assertThat(Totp.matchingCounter(seed, "12345", now)).isNull()
        assertThat(Totp.matchingCounter(seed, "abcdef", now)).isNull()
    }

    @Test
    @DisplayName("otpauth URI 는 base32 시드와 발급자를 담음")
    fun otpauthUri() {
        val uri = Totp.otpauthUri("Stockholm", "월터", seed)
        assertThat(uri)
            .startsWith("otpauth://totp/Stockholm:")
            .contains("secret=" + Base32.encode(seed))
            .contains("issuer=Stockholm")
            .contains("digits=6")
            .contains("period=30")
    }

    @Test
    @DisplayName("base32 는 왕복하고 패딩 없이 인코딩함")
    fun base32RoundTrip() {
        val bytes = ByteArray(20) { it.toByte() }
        val text = Base32.encode(bytes)
        assertThat(text).doesNotContain("=").matches("[A-Z2-7]+")
        assertThat(Base32.decode(text)).isEqualTo(bytes)
        assertThat(Base32.encode("foobar".toByteArray())).isEqualTo("MZXW6YTBOI")
    }
}
