package banghak.stock.support.web

import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.account.Session
import banghak.stock.core.domain.account.SessionKind
import banghak.stock.core.domain.identity.Role
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.shared.web.ErrorAdvice
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SpecVersion
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.springframework.core.MethodParameter
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * 컨트롤러를 Spring 컨텍스트 없이 HTTP 로 부르는 보조.
 * 세션은 고정 Principal, 오류는 ErrorAdvice, JSON 은 앱과 같이 Kotlin 모듈을 붙인 매퍼를 씀.
 */
object ApiTestSupport {
    val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    val principal: Principal =
        Principal(
            TradingFixtures.user,
            Role.ADMIN,
            Session(
                sessionId = "session-1",
                userId = TradingFixtures.user,
                deviceId = TradingFixtures.device,
                kind = SessionKind.NORMAL,
                issuedAt = TradingFixtures.now,
                expiresAt = TradingFixtures.now.plus(Duration.ofHours(12)),
                lastStepUpAt = null,
                revokedAt = null,
            ),
        )

    fun mockMvc(vararg controllers: Any): MockMvc =
        MockMvcBuilders.standaloneSetup(*controllers)
            .setCustomArgumentResolvers(FixedPrincipalResolver(principal))
            .setControllerAdvice(ErrorAdvice())
            .setMessageConverters(
                JacksonJsonHttpMessageConverter(mapper),
                StringHttpMessageConverter(StandardCharsets.UTF_8),
            )
            .build()

    fun toJson(body: Any): String = mapper.writeValueAsString(body)

    /** 응답 JSON 이 `protocol/schemas/api/<name>.schema.json` 을 통과하는지. */
    fun assertConforms(json: String, schemaName: String) {
        val node = ObjectMapper().readTree(json)
        assertThat(schema(schemaName).validate(node)).describedAs(json).isEmpty()
    }

    private fun schema(name: String): JsonSchema =
        JsonSchemaFactory.builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
            .schemaMappers {
                it.mapPrefix(SCHEMA_ID_PREFIX, SCHEMAS_DIR.toAbsolutePath().toUri().toString())
            }
            .build()
            .getSchema(
                SchemaLocation.of(SCHEMAS_DIR.resolve("api/$name.schema.json").toUri().toString())
            )

    private class FixedPrincipalResolver(private val principal: Principal) :
        HandlerMethodArgumentResolver {
        override fun supportsParameter(parameter: MethodParameter): Boolean =
            parameter.parameterType == Principal::class.java

        override fun resolveArgument(
            parameter: MethodParameter,
            mavContainer: ModelAndViewContainer?,
            webRequest: NativeWebRequest,
            binderFactory: WebDataBinderFactory?,
        ): Any = principal
    }

    private const val SCHEMA_ID_PREFIX = "https://stockholm.banghak/protocol/"
    private val SCHEMAS_DIR: Path = Path.of("..", "protocol", "schemas")
}
