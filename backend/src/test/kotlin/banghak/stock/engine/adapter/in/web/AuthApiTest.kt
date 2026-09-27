package banghak.stock.engine.adapter.`in`.web

import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.port.SecretStorePort
import banghak.stock.engine.adapter.out.keychain.KeychainTotpAdapter
import banghak.stock.engine.adapter.out.keychain.SecretReader
import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.crypto.Base32
import banghak.stock.shared.crypto.Totp
import banghak.stock.shared.web.LocalToken
import banghak.stock.support.ExternalApiStubs
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.MemorySecretStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
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
 * 마법사를 끝낸 설치에서 로그인 → 본인 API → step-up → admin API → 구성원 가입까지 HTTP 로 돌림. TOTP 코드는 메모리 저장소의 시드로 직접
 * 계산함(시드는 응답에 없음).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(RuntimeProfiles.ENGINE)
@Import(AuthApiTest.FakeSecrets::class)
class AuthApiTest {
    @TestConfiguration
    class FakeSecrets {
        @Bean @Primary fun memorySecretStore(): MemorySecretStore = MemorySecretStore()

        // TOTP 는 같은 30초 구간의 코드를 두 번 받지 않으므로, 단계 사이에 시계를 앞으로 돌림
        @Bean @Primary fun mutableClock(): MutableClock = MutableClock(Instant.now())
    }

    @Autowired private lateinit var environment: Environment
    @Autowired private lateinit var localToken: LocalToken
    @Autowired private lateinit var secretStore: SecretStorePort
    @Autowired private lateinit var clock: MutableClock

    private val http = HttpClient.newHttpClient()
    private var bearer: String? = null

    @Test
    @DisplayName("로그인·세션·step-up·회원 관리·가입이 API 경계대로 동작함")
    fun authenticationFlow() {
        val adminId = completeWizard()
        bearer = null

        assertThat(get("/session/users").body()).contains("\"displayName\":\"월터\"")
        assertThat(get("/me").statusCode()).describedAs("세션 없이 401").isEqualTo(401)
        assertThat(
                post(
                        "/session/login",
                        """{"userId":"$adminId","password":"wrong-password-123","totpCode":"000000"}""",
                    )
                    .statusCode()
            )
            .isEqualTo(401)
        val login =
            post(
                "/session/login",
                """{"userId":"$adminId","password":"correct-horse-battery","totpCode":"${totpCode(adminId)}"}""",
            )
        assertThat(login.statusCode()).describedAs(login.body()).isEqualTo(200)
        bearer = Regex("\"token\":\"([^\"]+)\"").find(login.body())?.groupValues?.get(1)

        assertThat(get("/me").body()).contains("\"role\":\"ADMIN\"")
        assertThat(get("/admin/members").statusCode()).describedAs("step-up 전 403").isEqualTo(403)
        assertThat(post("/session/step-up", """{"totpCode":"${totpCode(adminId)}"}""").statusCode())
            .isEqualTo(200)
        assertThat(get("/admin/members").body()).contains("\"displayName\":\"월터\"")

        val code =
            Regex("\"code\":\"([^\"]+)\"")
                .find(post("/admin/members/registration-codes", "{}").body())
                ?.groupValues
                ?.get(1)
                .orEmpty()
        val adminBearer = bearer
        bearer = null
        val registered =
            post(
                "/session/register",
                """{"registrationCode":"$code","displayName":"둘째","password":"correct-horse-battery","passwordConfirmation":"correct-horse-battery"}""",
            )
        assertThat(registered.statusCode()).isEqualTo(200)
        val memberId =
            Regex("\"userId\":\"([^\"]+)\"").find(registered.body())?.groupValues?.get(1).orEmpty()
        assertThat(
                post(
                        "/session/login",
                        """{"userId":"$memberId","password":"correct-horse-battery","totpCode":"000000"}""",
                    )
                    .statusCode()
            )
            .describedAs("TOTP 확인 전 로그인 불가")
            .isEqualTo(401)
        assertThat(
                post(
                        "/session/register/totp",
                        """{"userId":"$memberId","code":"${totpCode(memberId, pending = true)}"}""",
                    )
                    .statusCode()
            )
            .isEqualTo(200)
        val memberLogin =
            post(
                "/session/login",
                """{"userId":"$memberId","password":"correct-horse-battery","totpCode":"${totpCode(memberId)}"}""",
            )
        assertThat(memberLogin.statusCode()).isEqualTo(200)
        bearer = Regex("\"token\":\"([^\"]+)\"").find(memberLogin.body())?.groupValues?.get(1)
        assertThat(get("/admin/members").statusCode())
            .describedAs("구성원은 admin API 403")
            .isEqualTo(403)
        assertThat(get("/admin/credentials").statusCode()).isEqualTo(403)

        bearer = adminBearer
        assertThat(get("/admin/credentials").body())
            .contains("\"kind\":\"DART\"")
            .doesNotContain("MARKER")
        assertThat(post("/session/logout", "{}").statusCode()).isEqualTo(200)
        assertThat(get("/me").statusCode()).describedAs("로그아웃 뒤 401").isEqualTo(401)
    }

    private fun completeWizard(): String {
        val created =
            post(
                "/setup/admin",
                """{"displayName":"월터","password":"correct-horse-battery","passwordConfirmation":"correct-horse-battery"}""",
            )
        val userId =
            Regex("\"userId\":\"([^\"]+)\"").find(created.body())?.groupValues?.get(1).orEmpty()
        val confirmed =
            post("/setup/admin/totp", """{"code":"${totpCode(userId, pending = true)}"}""").body()
        bearer = Regex("\"wizardToken\":\"([^\"]+)\"").find(confirmed)?.groupValues?.get(1)
        post("/setup/keys/DART", """{"fields":{"VALUE":"MARKER-DART-KEY-0001"}}""")
        post("/setup/keys/OPENAI", """{"fields":{"VALUE":"sk-MARKER-OPENAI-0001"}}""")
        post("/setup/keys/done", """{"llmPreset":"BALANCED"}""")
        post("/setup/toss", """{"decision":"LATER"}""")
        assertThat(post("/setup/complete", "{}").body()).contains("\"state\":\"COMPLETE\"")
        return userId
    }

    private fun totpCode(userId: String, pending: Boolean = false): String {
        clock.advance(Duration.ofSeconds(31))
        val name =
            if (pending) KeychainTotpAdapter.PENDING_SEED_NAME else KeychainTotpAdapter.SEED_NAME
        val seed =
            (secretStore as SecretReader).read(
                SecretKey.user(banghak.stock.core.domain.identity.UserId(userId), name)
            ) ?: error("시드 없음")
        return Totp.generate(Base32.decode(String(seed.reveal())), Totp.counterAt(clock.instant()))
    }

    private fun get(path: String): HttpResponse<String> = send(request(path).GET().build())

    private fun post(path: String, body: String): HttpResponse<String> =
        send(
            request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        )

    private fun request(path: String): HttpRequest.Builder {
        val port = environment.getRequiredProperty("local.server.port")
        val builder =
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header(LocalToken.HEADER, Files.readString(localToken.file))
        bearer?.let { builder.header("Authorization", "Bearer $it") }
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
