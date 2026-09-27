package banghak.stock.engine

import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.LlmPreset
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.account.TossDecision
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.usecase.CreateAdminCommand
import banghak.stock.core.usecase.LoginCommand
import banghak.stock.core.usecase.LoginUseCase
import banghak.stock.core.usecase.SetupWizardUseCase
import banghak.stock.engine.adapter.out.keychain.KeychainTotpAdapter
import banghak.stock.engine.adapter.out.keychain.SecretReader
import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.crypto.Base32
import banghak.stock.shared.crypto.Totp
import banghak.stock.support.ExternalApiStubs
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.MemorySecretStore
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 1단계 완료 기준 "표식 비밀값 검색 0건": 표식이 든 키·비밀번호로 마법사와 로그인을 돌린 뒤 DB 전 표 덤프와 그동안의 로그 전체를 훑어 표식이 비밀 저장소 밖
 * 어디에도 없는지 확인함.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles(RuntimeProfiles.ENGINE)
@Import(SecretLeakSweepTest.Fakes::class)
class SecretLeakSweepTest {
    @TestConfiguration
    class Fakes {
        @Bean @Primary fun memorySecretStore(): MemorySecretStore = MemorySecretStore()

        @Bean @Primary fun mutableClock(): MutableClock = MutableClock(Instant.now())
    }

    @Autowired private lateinit var setup: SetupWizardUseCase
    @Autowired private lateinit var login: LoginUseCase
    @Autowired private lateinit var secrets: MemorySecretStore
    @Autowired private lateinit var clock: MutableClock
    @Autowired private lateinit var engineWriteDataSource: HikariDataSource

    private val markers =
        listOf(
            "MARKER-PW-correct-horse-9f1",
            "MARKER-DART-KEY-4c2e",
            "MARKER-OPENAI-KEY-77aa",
            "MARKER-TOSS-SECRET-b0d3",
        )

    @Test
    @DisplayName("마법사·로그인을 돌린 뒤 DB 덤프와 로그 어디에도 표식 비밀값이 없음")
    fun noMarkerOutsideSecretStore() {
        val captured = captureLogs()
        val password = markers[0]
        val created =
            setup.createAdmin(
                CreateAdminCommand("월터", password.toCharArray(), password.toCharArray())
            )
        setup.confirmAdminTotp(totpCode(created.userId, pending = true))
        setup.registerSharedCredential(
            CredentialKind.DART,
            mapOf("VALUE" to SecretValue.of(markers[1])),
        )
        setup.registerSharedCredential(
            CredentialKind.OPENAI,
            mapOf("VALUE" to SecretValue.of(markers[2])),
        )
        setup.finishSharedKeys(LlmPreset.BALANCED)
        setup.registerTossCredential(
            mapOf(
                "CLIENT_ID" to SecretValue.of("client-0001"),
                "CLIENT_SECRET" to SecretValue.of(markers[3]),
            )
        )
        setup.decideToss(TossDecision.REGISTERED)
        setup.complete()
        login.login(
            LoginCommand(created.userId, password.toCharArray(), totpCode(created.userId), null)
        )

        val dump = dumpAllTables()
        val logs =
            captured.list.joinToString("\n") {
                it.formattedMessage + (it.throwableProxy?.let(ThrowableProxyUtil::asString) ?: "")
            }
        for (marker in markers) {
            assertThat(dump).describedAs("DB 덤프에 표식 $marker").doesNotContain(marker)
            assertThat(logs).describedAs("로그에 표식 $marker").doesNotContain(marker)
        }
        assertThat(
                String(
                    secrets
                        .read(banghak.stock.core.domain.account.SecretKey.shared("DART"))
                        ?.reveal() ?: CharArray(0)
                )
            )
            .isEqualTo(markers[1])
    }

    private fun dumpAllTables(): String {
        val jdbc = JdbcTemplate(engineWriteDataSource)
        val tables =
            jdbc.queryForList(
                "select name from sqlite_master where type = 'table'",
                String::class.java,
            )
        return tables.joinToString("\n") { table ->
            "$table: " + jdbc.queryForList("select * from $table").joinToString { it.toString() }
        }
    }

    private val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val appenders = mutableListOf<ListAppender<ILoggingEvent>>()

    private fun captureLogs(): ListAppender<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        rootLogger.addAppender(appender)
        appenders += appender
        return appender
    }

    // 다른 테스트의 로그가 섞여 들어오거나 표식이 메모리에 남지 않게 붙인 appender 를 떼고 멈춤
    @AfterEach
    fun stopCapturing() {
        appenders.forEach {
            rootLogger.detachAppender(it)
            it.stop()
            it.list.clear()
        }
        appenders.clear()
    }

    private fun totpCode(userId: UserId, pending: Boolean = false): String {
        clock.advance(Duration.ofSeconds(31))
        val name =
            if (pending) KeychainTotpAdapter.PENDING_SEED_NAME else KeychainTotpAdapter.SEED_NAME
        val seed =
            (secrets as SecretReader).read(
                banghak.stock.core.domain.account.SecretKey.user(userId, name)
            ) ?: error("시드 없음")
        return Totp.generate(Base32.decode(String(seed.reveal())), Totp.counterAt(clock.instant()))
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun dataDir(registry: DynamicPropertyRegistry) {
            ExternalApiStubs.register(registry)
            registry.add("stockholm.data-dir") {
                Files.createTempDirectory("stockholm-test-").toString()
            }
            registry.add("logging.level.banghak.stock") { "DEBUG" }
            registry.add("logging.level.org.hibernate.SQL") { "DEBUG" }
            registry.add("logging.level.org.hibernate.orm.jdbc.bind") { "TRACE" }
        }
    }
}
