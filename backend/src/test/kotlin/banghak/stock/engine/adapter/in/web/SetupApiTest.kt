package banghak.stock.engine.adapter.`in`.web

import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.port.SecretStorePort
import banghak.stock.engine.adapter.out.keychain.SecretReader
import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.crypto.Base32
import banghak.stock.shared.crypto.Totp
import banghak.stock.shared.web.LocalToken
import banghak.stock.support.ExternalApiStubs
import banghak.stock.support.fakes.MemorySecretStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Clock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 로컬 토큰·SetupGate·마법사 API 를 HTTP 로 끝까지 돌림. 비밀 저장소는 메모리 fake 로 바꿔 실제 Keychain 을 건드리지 않음. 순서가 있는
 * 시나리오라 메서드 순서를 고정함.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(RuntimeProfiles.ENGINE)
@Import(SetupApiTest.FakeSecrets::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SetupApiTest {
    @TestConfiguration
    class FakeSecrets {
        @Bean @Primary fun memorySecretStore(): MemorySecretStore = MemorySecretStore()
    }

    @Autowired private lateinit var environment: Environment
    @Autowired private lateinit var localToken: LocalToken
    @Autowired private lateinit var secretStore: SecretStorePort
    @Autowired private lateinit var clock: Clock

    private val http = HttpClient.newHttpClient()
    private var wizardToken: String? = null
    private val marker = "MARKER-API-KEY-9c1e"

    @Test
    @Order(1)
    @DisplayName("헬스는 토큰 없이, 다른 경로는 토큰 없이는 401, 마법사 전에는 setup 외 403")
    fun tokenAndGate() {
        assertThat(get("/actuator/health", withToken = false).statusCode()).isEqualTo(200)
        assertThat(get("/setup/state", withToken = false).statusCode()).isEqualTo(401)
        assertThat(get("/setup/state").statusCode()).isEqualTo(200)
        assertThat(get("/stocks/anything").statusCode()).isEqualTo(403)
        assertThat(Files.getPosixFilePermissions(localToken.file).toString())
            .isEqualTo("[OWNER_READ, OWNER_WRITE]")
    }

    @Test
    @Order(2)
    @DisplayName("마법사 ①~④ 를 API 로 완주하면 COMPLETE 가 되고 setup 은 404, 다른 경로는 열림")
    fun fullWizard() {
        assertThat(get("/setup/state").body()).contains("\"state\":\"NOT_STARTED\"")
        assertThat(get("/setup/catalog").body())
            .contains("\"kind\":\"DART\"")
            .contains("\"kind\":\"TOSS\"")

        val created =
            post(
                "/setup/admin",
                """{"displayName":"월터","password":"correct-horse-battery","passwordConfirmation":"correct-horse-battery"}""",
            )
        assertThat(created.statusCode()).isEqualTo(200)
        assertThat(created.body())
            .contains("totpQrPngBase64")
            .doesNotContain("correct-horse-battery")
        val userId =
            Regex("\"userId\":\"([^\"]+)\"").find(created.body())?.groupValues?.get(1).orEmpty()
        val manualKey =
            Regex("\"totpManualKey\":\"([^\"]+)\"")
                .find(created.body())
                ?.groupValues
                ?.get(1)
                .orEmpty()

        assertThat(post("/setup/admin/totp", """{"code":"000000"}""").statusCode()).isEqualTo(401)
        val code = Totp.generate(Base32.decode(manualKey), Totp.counterAt(clock.instant()))
        val confirmed = post("/setup/admin/totp", """{"code":"$code"}""").body()
        assertThat(confirmed).contains("\"state\":\"ADMIN_CREATED\"").contains("wizardToken")
        assertThat(post("/setup/keys/DART", """{"fields":{"VALUE":"$marker"}}""").statusCode())
            .describedAs("마법사 세션 없이는 401")
            .isEqualTo(401)
        assertThat(get("/setup/catalog").statusCode())
            .describedAs("카탈로그는 마법사 세션 없이도 열림")
            .isEqualTo(200)
        // 앱 재시작을 흉내 냄: 받은 토큰을 버리고 비밀번호 + 다음 TOTP 코드로 마법사 세션을 다시 엶
        wizardToken = null
        assertThat(
                post("/setup/session", """{"password":"wrong-password-123","totpCode":"000000"}""")
                    .statusCode()
            )
            .isEqualTo(401)
        val nextCode = Totp.generate(Base32.decode(manualKey), Totp.counterAt(clock.instant()) + 1)
        val reopened =
            post(
                "/setup/session",
                """{"password":"correct-horse-battery","totpCode":"$nextCode"}""",
            )
        assertThat(reopened.statusCode()).describedAs(reopened.body()).isEqualTo(200)
        wizardToken =
            Regex("\"wizardToken\":\"([^\"]+)\"").find(reopened.body())?.groupValues?.get(1)

        assertThat(post("/setup/keys/DART", """{"fields":{"VALUE":"$marker"}}""").body())
            .contains("\"result\":\"OK\"")
        assertThat(post("/setup/keys/OPENAI", """{"fields":{"VALUE":"sk-$marker"}}""").body())
            .contains("\"result\":\"OK\"")
        assertThat(post("/setup/keys/KRX", """{"fields":{"VALUE":"x"}}""").body())
            .contains("\"result\":\"REJECTED\"")
        val state = get("/setup/state").body()
        assertThat(state).contains("\"canFinishSharedKeys\":true").doesNotContain(marker)
        assertThat(post("/setup/keys/done", """{"llmPreset":"BALANCED"}""").body())
            .contains("\"state\":\"SHARED_KEYS_DONE\"")

        assertThat(post("/setup/toss", """{"decision":"REGISTERED"}""").statusCode()).isEqualTo(409)
        assertThat(
                post(
                        "/setup/toss/keys",
                        """{"fields":{"CLIENT_ID":"client-0001","CLIENT_SECRET":"$marker-secret"}}""",
                    )
                    .body()
            )
            .contains("\"result\":\"OK\"")
        assertThat(post("/setup/toss", """{"decision":"REGISTERED"}""").body())
            .contains("\"state\":\"TOSS_DECIDED\"")
        assertThat(post("/setup/complete", "{}").body()).contains("\"state\":\"COMPLETE\"")

        assertThat(get("/setup/state").statusCode()).isEqualTo(404)
        assertThat(get("/stocks/anything").statusCode()).isEqualTo(404)
        assertThat(secretStore.exists(SecretKey.shared("DART"))).isTrue()
        assertThat(
                String(
                    (secretStore as SecretReader)
                        .read(
                            SecretKey.user(
                                banghak.stock.core.domain.identity.UserId(userId),
                                "TOSS_CLIENT_SECRET",
                            )
                        )
                        ?.reveal() ?: CharArray(0)
                )
            )
            .isEqualTo("$marker-secret")
        assertThat(secretStore.exists(SecretKey.shared("KRX"))).isFalse()
    }

    private fun get(path: String, withToken: Boolean = true): HttpResponse<String> =
        send(request(path, withToken).GET().build())

    private fun post(path: String, body: String): HttpResponse<String> =
        send(
            request(path, true)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        )

    private fun request(path: String, withToken: Boolean): HttpRequest.Builder {
        val port = environment.getRequiredProperty("local.server.port")
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
        if (withToken) builder.header(LocalToken.HEADER, Files.readString(localToken.file))
        wizardToken?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun send(request: HttpRequest): HttpResponse<String> =
        http.send(request, HttpResponse.BodyHandlers.ofString())

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun dataDir(registry: DynamicPropertyRegistry) {
            ExternalApiStubs.register(registry)
            registry.add("stockholm.data-dir") {
                Files.createTempDirectory("stockholm-test-").toString()
            }
        }
    }
}
