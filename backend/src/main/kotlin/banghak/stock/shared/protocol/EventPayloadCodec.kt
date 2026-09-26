package banghak.stock.shared.protocol

import banghak.stock.core.domain.eventlog.DomainEvent
import com.fasterxml.jackson.annotation.JsonAutoDetect
import java.math.BigDecimal
import kotlin.reflect.KClass
import org.springframework.stereotype.Component
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.ser.std.StdSerializer
import tools.jackson.module.kotlin.KotlinModule

/** 이벤트 payload ↔ JSON. 이벤트 종류는 `type`(클래스 이름)으로 고르므로 JSON 안에 타입 정보를 넣지 않음. 금액은 문자열로 적어 자릿수를 보존함. */
@Component
class EventPayloadCodec {
    private val mapper: JsonMapper =
        JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .addModule(
                SimpleModule("plain-decimal").addSerializer(BigDecimal::class.java, PlainDecimal())
            )
            // 계산 속성(isPositive 등)은 적지 않고 생성자 인자에 대응하는 필드만 적음
            .changeDefaultVisibility { visibility ->
                visibility
                    .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                    .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                    .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
            }
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build()

    private val typesByName: Map<String, KClass<out DomainEvent>> =
        DomainEvent::class.sealedSubclasses.associateBy {
            it.simpleName ?: error("이벤트 클래스에 이름이 없음")
        }

    fun encode(event: DomainEvent): String = mapper.writeValueAsString(event)

    fun decode(type: String, json: String): DomainEvent {
        val kClass = typesByName[type] ?: throw IllegalArgumentException("알 수 없는 이벤트 종류: $type")
        return mapper.readValue(json, kClass.java)
    }

    private class PlainDecimal : StdSerializer<BigDecimal>(BigDecimal::class.java) {
        override fun serialize(
            value: BigDecimal,
            generator: JsonGenerator,
            context: SerializationContext,
        ) {
            generator.writeString(value.toPlainString())
        }
    }

    companion object {
        /** payload 스키마 버전. 필드를 호환되지 않게 바꾸면 올리고 구버전 읽기 경로를 둠. */
        const val CURRENT_VERSION = 1
    }
}
