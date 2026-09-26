package banghak.stock.shared.config

import java.math.BigDecimal
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.JacksonModule
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.ser.std.StdSerializer

/** 로컬 API의 금액은 문자열로 내보냄. 화면은 금액을 `number`로 계산하지 않음. */
@Configuration
class JacksonConfig {
    @Bean
    fun plainDecimalModule(): JacksonModule =
        SimpleModule("plain-decimal")
            .addSerializer(BigDecimal::class.java, PlainDecimalSerializer())
}

private class PlainDecimalSerializer : StdSerializer<BigDecimal>(BigDecimal::class.java) {
    override fun serialize(
        value: BigDecimal,
        generator: JsonGenerator,
        context: SerializationContext,
    ) {
        generator.writeString(value.toPlainString())
    }
}
