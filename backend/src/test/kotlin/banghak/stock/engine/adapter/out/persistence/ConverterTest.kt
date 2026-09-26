package banghak.stock.engine.adapter.out.persistence

import banghak.stock.engine.adapter.out.persistence.converter.DecimalTextConverter
import banghak.stock.engine.adapter.out.persistence.converter.InstantTextConverter
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ConverterTest {
    private val decimal = DecimalTextConverter()
    private val instant = InstantTextConverter()

    @Test
    @DisplayName("BigDecimal 은 17자리 이상 값도 자릿수까지 그대로 돌아옴")
    fun decimalRoundTripKeepsScale() {
        for (text in listOf("12345678901234567.12345678", "0.000001", "100.00", "-1000", "0")) {
            val value = BigDecimal(text)
            val stored = decimal.convertToDatabaseColumn(value)
            assertThat(stored).doesNotContain("E")
            assertThat(decimal.convertToEntityAttribute(stored)).isEqualTo(value)
        }
    }

    @Test
    @DisplayName("지수 표기(음수 scale)는 평문 숫자로 정규화되어 저장됨")
    fun negativeScaleIsStoredAsPlainNumber() {
        assertThat(decimal.convertToDatabaseColumn(BigDecimal("-1E+3"))).isEqualTo("-1000")
    }

    @Test
    @DisplayName("Instant 는 밀리초 3자리 고정 UTC 문자열이라 사전순이 시간순임")
    fun instantTextSortsChronologically() {
        val times =
            listOf(
                Instant.parse("2026-09-27T00:00:00Z"),
                Instant.parse("2026-09-27T00:00:00.007Z"),
                Instant.parse("2026-09-27T00:00:00.070Z"),
                Instant.parse("2026-09-27T00:00:01Z"),
                Instant.parse("2027-01-01T00:00:00Z"),
            )
        val texts = times.map { instant.convertToDatabaseColumn(it) }
        assertThat(texts).allMatch {
            it?.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")) == true
        }
        assertThat(texts).isSorted()
        assertThat(texts.map { instant.convertToEntityAttribute(it) }).isEqualTo(times)
    }
}
