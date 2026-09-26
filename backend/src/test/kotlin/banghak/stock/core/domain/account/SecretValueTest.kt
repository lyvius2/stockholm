package banghak.stock.core.domain.account

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SecretValueTest {
    private val marker = "MARKER-SECRET-9f2c"

    @Test
    @DisplayName("toString 에 값이 나오지 않고 끝 4자리만 노출함")
    fun neverPrintsValue() {
        val value = SecretValue.of(marker)
        assertThat(value.toString()).doesNotContain(marker)
        assertThat(value.last4).isEqualTo("9f2c")
        assertThat(String(value.reveal())).isEqualTo(marker)
    }

    @Test
    @DisplayName("빈 값은 거부함")
    fun rejectsBlank() {
        assertThatThrownBy { SecretValue.of("   ") }.isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("wipe 뒤에는 원본 문자가 남지 않음")
    fun wipeClearsChars() {
        val value = SecretValue.of(marker)
        value.wipe()
        assertThat(String(value.reveal())).doesNotContain("MARKER")
    }

    @Test
    @DisplayName("SecretKey 경로는 범위·사용자·이름으로 정해지고 형식을 검증함")
    fun keyPath() {
        val user = UserId.from(Ulid.of(Instant.parse("2026-09-27T00:00:00Z"), ByteArray(10)))
        assertThat(SecretKey.shared("DART").path).isEqualTo("stockholm/shared/DART")
        assertThat(SecretKey.user(user, "TOSS_CLIENT_SECRET").path)
            .isEqualTo("stockholm/user/$user/TOSS_CLIENT_SECRET")
        assertThatThrownBy { SecretKey.shared("dart") }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { SecretKey(SecretScope.USER, "TOSS") }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { SecretKey(SecretScope.SHARED, "DART", user) }
            .isInstanceOf(InvalidValueException::class.java)
    }
}
