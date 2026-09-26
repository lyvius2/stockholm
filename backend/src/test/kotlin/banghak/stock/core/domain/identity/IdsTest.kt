package banghak.stock.core.domain.identity

import banghak.stock.core.domain.error.InvalidValue
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class IdsTest {
    private val ulid = Ulid.of(Instant.parse("2026-09-26T00:00:00Z"), ByteArray(10))

    @Test
    @DisplayName("UserId는 u_ 접두 + ULID")
    fun userIdIsPrefixedUlid() {
        assertThat(UserId.from(ulid).value).isEqualTo("u_${ulid.value}")
        assertThat(UserId("u_${ulid.value}")).isEqualTo(UserId.from(ulid))
        assertThatThrownBy { UserId("d_${ulid.value}") }.isInstanceOf(InvalidValue::class.java)
        assertThatThrownBy { UserId("u_short") }.isInstanceOf(InvalidValue::class.java)
    }

    @Test
    @DisplayName("DeviceId는 d_ 접두 + ULID")
    fun deviceIdIsPrefixedUlid() {
        assertThat(DeviceId.from(ulid).value).isEqualTo("d_${ulid.value}")
        assertThatThrownBy { DeviceId("u_${ulid.value}") }.isInstanceOf(InvalidValue::class.java)
    }
}
