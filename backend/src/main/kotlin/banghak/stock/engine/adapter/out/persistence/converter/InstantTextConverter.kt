package banghak.stock.engine.adapter.out.persistence.converter

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `Instant` ↔ ISO-8601 UTC 문자열. 밀리초 3자리를 고정해 문자열 사전순이 시간순과 같게 함. 그래야 `occurred_at` 범위 조회가 인덱스를 탐.
 */
@Converter(autoApply = true)
class InstantTextConverter : AttributeConverter<Instant, String> {
    override fun convertToDatabaseColumn(attribute: Instant?): String? = attribute?.let {
        FORMAT.format(it)
    }

    override fun convertToEntityAttribute(dbData: String?): Instant? = dbData?.let {
        Instant.parse(it)
    }

    companion object {
        val FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
