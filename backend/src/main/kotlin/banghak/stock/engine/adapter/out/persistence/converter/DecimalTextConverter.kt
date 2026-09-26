package banghak.stock.engine.adapter.out.persistence.converter

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.math.BigDecimal

/** `BigDecimal` ↔ TEXT. 지수 표기 없이 자릿수(scale)까지 그대로 보존함. 합산·정렬은 DB가 아니라 Kotlin에서 함. */
@Converter(autoApply = true)
class DecimalTextConverter : AttributeConverter<BigDecimal, String> {
    override fun convertToDatabaseColumn(attribute: BigDecimal?): String? =
        attribute?.toPlainString()

    override fun convertToEntityAttribute(dbData: String?): BigDecimal? = dbData?.let {
        BigDecimal(it)
    }
}
