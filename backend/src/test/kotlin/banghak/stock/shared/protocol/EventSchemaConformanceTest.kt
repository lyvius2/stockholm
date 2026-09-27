package banghak.stock.shared.protocol

import banghak.stock.core.domain.eventlog.DomainEvent
import banghak.stock.core.domain.eventlog.LotOpened
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.portfolio.BuyOrigin
import banghak.stock.core.domain.portfolio.LotId
import banghak.stock.core.domain.trading.Quantity
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SpecVersion
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.name
import kotlin.streams.asSequence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `protocol/schemas/events` 의 스키마와 코덱이 실제로 내는 JSON 이 어긋나지 않아야 함. 스키마가 있는 이벤트마다 표본을 두고, 표본이 없는 스키마는
 * 실패로 봄(스키마만 먼저 생기는 것을 막음).
 */
class EventSchemaConformanceTest {
    private val codec = EventPayloadCodec()
    private val t0 = Instant.parse("2026-09-27T00:00:00Z")

    private val samples: Map<String, DomainEvent> =
        mapOf(
            "LotOpened" to
                LotOpened(
                    LotId.from(Ulid.of(t0, ByteArray(10))),
                    Symbol(Market.US, "NVDA"),
                    Quantity.of("1.5"),
                    Money.of("123.40", Currency.USD),
                    BuyOrigin.AUTO_BUY,
                    ExchangeRate(Currency.USD, Currency.KRW, BigDecimal("1350.55"), t0),
                    t0,
                ),
            "LotOpened(fx 없음)" to
                LotOpened(
                    LotId.from(Ulid.of(t0, ByteArray(10))),
                    Symbol(Market.KR, "005930"),
                    Quantity.of(10),
                    Money.of("70000", Currency.KRW),
                    BuyOrigin.MANUAL,
                    null,
                    t0,
                ),
        )

    @Test
    @DisplayName("이벤트 스키마마다 코덱 출력이 스키마를 통과함")
    fun codecOutputConformsToSchemas() {
        // $id 의 https 주소를 로컬 폴더로 매핑해 $ref 를 네트워크 없이 풀게 함
        val factory =
            JsonSchemaFactory.builder(
                    JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                )
                .schemaMappers { mappers ->
                    mappers.mapPrefix(
                        SCHEMA_ID_PREFIX,
                        SCHEMAS_DIR.toAbsolutePath().toUri().toString(),
                    )
                }
                .build()
        val schemas =
            Files.list(EVENTS_DIR).use {
                it.asSequence().filter { p -> p.name.endsWith(".schema.json") }.toList()
            }
        assertThat(schemas).describedAs("이벤트 스키마가 하나는 있어야 함").isNotEmpty()
        for (schemaPath in schemas) {
            val typeName = pascalOf(schemaPath.name.removeSuffix(".schema.json"))
            val schema = factory.getSchema(SchemaLocation.of(schemaPath.toUri().toString()))
            val matching = samples.filterKeys { it == typeName || it.startsWith("$typeName(") }
            assertThat(matching).describedAs("$typeName 의 표본 이벤트가 없음").isNotEmpty()
            for ((label, event) in matching) {
                assertThat(EventPayloadCodec.CURRENT_VERSION).isEqualTo(1)
                val json = codec.encode(event)
                val errors = schema.validate(codec.readTree(json))
                assertThat(errors).describedAs("$label → $json").isEmpty()
            }
        }
    }

    private fun pascalOf(kebab: String): String =
        kebab.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

    companion object {
        private const val SCHEMA_ID_PREFIX = "https://stockholm.banghak/protocol/"
        private val SCHEMAS_DIR: Path = Path.of("..", "protocol", "schemas")
        private val EVENTS_DIR: Path = SCHEMAS_DIR.resolve("events")
    }
}
